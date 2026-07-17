#include <M5StickCPlus.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <string.h>

// BLE identifiers
#define DEVICE_NAME     "QuotaWatch"
#define SERVICE_UUID    "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
#define QUOTA_CHAR_UUID "a1b2c3d4-e5f6-7890-abcd-ef1234567891"

// Display (landscape: 240x135)
#define SCREEN_W 240
#define SCREEN_H 135

// Colors
#define C_BG      TFT_BLACK
#define C_HEADER  0x1082
#define C_BAR_BG  0x2104
#define C_TEXT    TFT_WHITE
#define C_DIM     0x8410
#define C_BLUE    0x04FF

// Quota data
// Cap raised from 6 to 12 so a second page of per-model (Fable/Spark) rows
// fits. Memory: 12 * sizeof(Quota) (32B) = 384B static — trivial on the
// ESP32-PICO-D4's 520KB SRAM. The parse buffer (buf[512] in parseQuotaData)
// also bounds intake: 12 lines of "Name:used:limit:unit" (~40B worst case)
// = ~480B, and 512 is the practical ceiling of a single BLE write anyway
// (max negotiated ATT MTU 517 → 514B payload).
#define MAX_QUOTAS 12

// Quotas render in fixed-size pages; each page lays out independently with the
// existing font/bar rules (compact layout kicks in above 3 rows on a page).
#define PAGE_SIZE  6

struct Quota {
    char name[16];
    float used;
    float limit;
    char unit[8];
};

static Quota quotas[MAX_QUOTAS];
static int numQuotas = 0;
static int currentPage = 0;
static bool bleConnected = false;
static bool needsRedraw = true;
static unsigned long lastUpdateTime = 0;
static unsigned long lastFooterRedraw = 0;
static bool screenOn = true;
static int brightness = 15;  // last-set brightness; restored on wake

// Pages needed for the current quota count. Always >= 1 so callers can safely
// take (page % totalPages()) without a divide-by-zero when numQuotas == 0.
static int totalPages() {
    if (numQuotas <= 0) return 1;
    return (numQuotas + PAGE_SIZE - 1) / PAGE_SIZE;
}

static BLEServer* pServer = nullptr;

// --- Parsing ---
// Protocol: newline-separated lines of "Name:used:limit:unit"
// Example: "Claude:17:100:%\nCodex:42:100:%\nActions:465:3000:min"
// An empty payload is valid and means "zero quotas now".
//
// onWrite runs in the BLE host's FreeRTOS task, separate from the UI loop, and
// we deliberately take no lock here (a general lock-free scheme is a filed
// follow-up). To keep the torn-read window minimal we parse into a private
// scratch, then publish quotas[]/currentPage/numQuotas together at the end with
// numQuotas written *last* as the commit — so a redraw that observes the new
// count also sees the matching rows and page. drawScreen() additionally guards
// the shrink transient so it can never divide by zero.
static void parseQuotaData(const char* data, size_t len) {
    char buf[512];
    size_t n = (len < sizeof(buf) - 1) ? len : sizeof(buf) - 1;
    memcpy(buf, data, n);
    buf[n] = '\0';

    static Quota scratch[MAX_QUOTAS];  // BLE-task-private; never read by the UI task
    int count = 0;
    char* saveptr = nullptr;
    char* line = strtok_r(buf, "\n", &saveptr);

    while (line && count < MAX_QUOTAS) {
        Quota* q = &scratch[count];
        if (sscanf(line, "%15[^:]:%f:%f:%7s",
                   q->name, &q->used, &q->limit, q->unit) == 4) {
            count++;
        }
        line = strtok_r(nullptr, "\n", &saveptr);
    }

    // Keep the current page across refreshes; fall back to page 1 only if it no
    // longer exists (quota count shrank). Computed here, off the live count.
    int pages = (count <= 0) ? 1 : (count + PAGE_SIZE - 1) / PAGE_SIZE;
    int page = (currentPage >= pages) ? 0 : currentPage;

    // Publish. Rows first, then page, then numQuotas last as the commit.
    memcpy(quotas, scratch, sizeof(Quota) * count);
    currentPage = page;
    numQuotas = count;
}

// --- Display helpers ---

static uint16_t barColor(float pct) {
    if (pct < 0.50f) return TFT_GREEN;
    if (pct < 0.75f) return TFT_YELLOW;
    if (pct < 0.90f) return 0xFDA0; // orange
    return TFT_RED;
}

static int batteryPercent() {
    float v = M5.Axp.GetBatVoltage();
    int pct = (int)((v - 3.0f) / (4.2f - 3.0f) * 100.0f);
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    return pct;
}

static void drawHeader() {
    M5.Lcd.fillRect(0, 0, SCREEN_W, 22, C_HEADER);
    M5.Lcd.setTextFont(2);
    M5.Lcd.setTextSize(1);
    M5.Lcd.setTextColor(C_TEXT, C_HEADER);
    M5.Lcd.setCursor(6, 3);
    M5.Lcd.print("Quota Monitor");

    // BLE indicator
    uint16_t btColor = bleConnected ? C_BLUE : C_DIM;
    M5.Lcd.setTextColor(btColor, C_HEADER);
    M5.Lcd.setCursor(SCREEN_W - 44, 3);
    M5.Lcd.print("BT");
    if (bleConnected) {
        M5.Lcd.fillCircle(SCREEN_W - 10, 11, 4, C_BLUE);
    } else {
        M5.Lcd.drawCircle(SCREEN_W - 10, 11, 4, C_DIM);
    }
}

static void drawQuotaRow(int idx, int y) {
    Quota* q = &quotas[idx];
    float pct = (q->limit > 0) ? (q->used / q->limit) : 0.0f;
    if (pct > 1.0f) pct = 1.0f;

    int barX = 60;
    int barW = 95;
    int barH = 14;
    int barY = y + 6;
    int textX = barX + barW + 4;

    M5.Lcd.fillRect(0, y, SCREEN_W, 28, C_BG);

    M5.Lcd.setTextFont(2);
    M5.Lcd.setTextSize(1);
    M5.Lcd.setTextColor(C_TEXT, C_BG);
    M5.Lcd.setCursor(4, y + 4);
    M5.Lcd.print(q->name);

    M5.Lcd.fillRect(barX, barY, barW, barH, C_BAR_BG);
    int fillW = (int)(pct * barW);
    if (fillW > 0) {
        M5.Lcd.fillRect(barX, barY, fillW, barH, barColor(pct));
    }

    char txt[24];
    if (strcmp(q->unit, "%") == 0) {
        snprintf(txt, sizeof(txt), "%d%%", (int)(pct * 100));
    } else if (q->limit > 0) {
        snprintf(txt, sizeof(txt), "%.0f/%.0f", q->used, q->limit);
    } else {
        snprintf(txt, sizeof(txt), "%.0f %s", q->used, q->unit);
    }
    M5.Lcd.setTextColor(C_TEXT, C_BG);
    M5.Lcd.setCursor(textX, y + 4);
    M5.Lcd.print(txt);
}

// Compact variant for 4+ quotas: font 1 (8px), 8px bar, fits in ~18px rows
static void drawQuotaRowCompact(int idx, int y, int rowH) {
    Quota* q = &quotas[idx];
    float pct = (q->limit > 0) ? (q->used / q->limit) : 0.0f;
    if (pct > 1.0f) pct = 1.0f;

    int textY = y + (rowH - 8) / 2;
    int barX = 70;
    int barW = 110;
    int barH = 8;
    int barY = y + (rowH - barH) / 2;
    int textX = barX + barW + 4;

    M5.Lcd.fillRect(0, y, SCREEN_W, rowH, C_BG);

    M5.Lcd.setTextFont(1);
    M5.Lcd.setTextSize(1);
    M5.Lcd.setTextColor(C_TEXT, C_BG);
    M5.Lcd.setCursor(4, textY);
    M5.Lcd.print(q->name);

    M5.Lcd.fillRect(barX, barY, barW, barH, C_BAR_BG);
    int fillW = (int)(pct * barW);
    if (fillW > 0) {
        M5.Lcd.fillRect(barX, barY, fillW, barH, barColor(pct));
    }

    char txt[24];
    if (strcmp(q->unit, "%") == 0) {
        snprintf(txt, sizeof(txt), "%d%%", (int)(pct * 100));
    } else if (q->limit > 0) {
        snprintf(txt, sizeof(txt), "%.0f/%.0f", q->used, q->limit);
    } else {
        snprintf(txt, sizeof(txt), "%.0f%s", q->used, q->unit);
    }
    M5.Lcd.setTextColor(C_TEXT, C_BG);
    M5.Lcd.setCursor(textX, textY);
    M5.Lcd.print(txt);
}

static void drawFooter() {
    int y = SCREEN_H - 20;
    M5.Lcd.fillRect(0, y, SCREEN_W, 20, C_HEADER);
    M5.Lcd.setTextFont(2);
    M5.Lcd.setTextSize(1);
    M5.Lcd.setTextColor(C_TEXT, C_HEADER);

    // Battery
    char batStr[16];
    snprintf(batStr, sizeof(batStr), "BAT %d%%", batteryPercent());
    M5.Lcd.setCursor(6, y + 2);
    M5.Lcd.print(batStr);

    // Page indicator (centered) — only shown when there's more than one page.
    int pages = totalPages();
    if (pages > 1) {
        char pageStr[12];
        snprintf(pageStr, sizeof(pageStr), "%d/%d", currentPage + 1, pages);
        int w = M5.Lcd.textWidth(pageStr);
        M5.Lcd.setTextColor(C_TEXT, C_HEADER);
        M5.Lcd.setCursor((SCREEN_W - w) / 2, y + 2);
        M5.Lcd.print(pageStr);
    }

    // Last update time
    M5.Lcd.setCursor(SCREEN_W - 90, y + 2);
    if (lastUpdateTime > 0) {
        unsigned long ago = (millis() - lastUpdateTime) / 1000;
        char agoStr[24];
        if (ago < 60)
            snprintf(agoStr, sizeof(agoStr), "%lus ago", ago);
        else if (ago < 3600)
            snprintf(agoStr, sizeof(agoStr), "%lum ago", ago / 60);
        else
            snprintf(agoStr, sizeof(agoStr), "%luh ago", ago / 3600);
        M5.Lcd.print(agoStr);
    } else {
        M5.Lcd.setTextColor(C_DIM, C_HEADER);
        M5.Lcd.print("No data");
    }
}

static void drawScreen() {
    M5.Lcd.fillScreen(C_BG);
    drawHeader();

    if (numQuotas == 0) {
        M5.Lcd.setTextFont(2);
        M5.Lcd.setTextSize(1);
        M5.Lcd.setTextColor(C_DIM, C_BG);
        M5.Lcd.setCursor(30, 50);
        M5.Lcd.print("Waiting for phone...");
        M5.Lcd.setCursor(30, 70);
        M5.Lcd.print("Connect via BLE");
    } else {
        int startIdx = currentPage * PAGE_SIZE;
        int count = numQuotas - startIdx;
        if (count > PAGE_SIZE) count = PAGE_SIZE;

        // Defensive: a concurrent BLE publish (separate task, no lock) can
        // briefly leave currentPage pointing past a just-shrunk numQuotas.
        // Skip rows this frame rather than divide by zero on rowH below;
        // onWrite's needsRedraw repaints once the publish settles.
        if (count > 0) {
            // Lay out this page based on the number of rows it holds, not the total.
            int rowH = (SCREEN_H - 44) / count;
            if (rowH > 28) rowH = 28;
            bool compact = (count > 3);
            for (int i = 0; i < count; i++) {
                if (compact) {
                    drawQuotaRowCompact(startIdx + i, 24 + i * rowH, rowH);
                } else {
                    drawQuotaRow(startIdx + i, 24 + i * rowH);
                }
            }
        }
    }

    drawFooter();
}

// --- BLE Callbacks ---

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* server) override {
        bleConnected = true;
        needsRedraw = true;
    }
    void onDisconnect(BLEServer* server) override {
        bleConnected = false;
        needsRedraw = true;
        server->startAdvertising();
    }
};

class QuotaWriteCallback : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        std::string val = pChar->getValue();
        // Process every write, including empty ones: an empty payload means the
        // latest refresh has zero quotas and must clear the stale display (the
        // numQuotas==0 path renders the waiting screen) rather than be ignored.
        parseQuotaData(val.c_str(), val.length());
        lastUpdateTime = millis();
        needsRedraw = true;
    }
};

// --- BLE Setup ---

static void setupBLE() {
    BLEDevice::init(DEVICE_NAME);
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService* pService = pServer->createService(SERVICE_UUID);

    BLECharacteristic* pQuotaChar = pService->createCharacteristic(
        QUOTA_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pQuotaChar->setCallbacks(new QuotaWriteCallback());

    pService->start();

    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    BLEDevice::startAdvertising();
}

// --- Arduino entry points ---

void setup() {
    M5.begin();
    M5.Lcd.setRotation(1);
    M5.Lcd.fillScreen(C_BG);
    M5.Axp.ScreenBreath(15);  // max brightness

    setupBLE();
    drawScreen();
}

void loop() {
    M5.update();

    // Button A (front). When asleep, wake immediately on press so a long hold in
    // the dark lights up at once instead of staying black until release; that
    // waking press is then consumed so its release neither pages nor toggles.
    // When awake, action happens on release: short press = next page, long press
    // (>=800ms) = sleep. The 800ms threshold is comfortably above an intentional
    // tap yet short enough to feel deliberate as a "hold to sleep" gesture.
    static bool consumeRelease = false;  // swallow the release of a wake press
    if (M5.BtnA.wasPressed() && !screenOn) {
        screenOn = true;
        M5.Axp.ScreenBreath(brightness);
        needsRedraw = true;
        consumeRelease = true;
    }

    if (M5.BtnA.wasReleasefor(800)) {
        if (consumeRelease) {
            consumeRelease = false;  // wake hold released; nothing further
        } else {
            // Long press while awake: sleep.
            screenOn = false;
            M5.Axp.ScreenBreath(0);
        }
    } else if (M5.BtnA.wasReleased()) {
        if (consumeRelease) {
            consumeRelease = false;  // wake tap released; nothing further
        } else {
            // Short press while awake: next page, wrapping around.
            currentPage = (currentPage + 1) % totalPages();
            needsRedraw = true;
        }
    }

    // Button B (side): cycle brightness
    if (M5.BtnB.wasPressed()) {
        brightness = (brightness == 15) ? 9 : 15;
        M5.Axp.ScreenBreath(brightness);
        screenOn = true;
    }

    // Redraw on new data or state change
    if (needsRedraw && screenOn) {
        drawScreen();
        needsRedraw = false;
        lastFooterRedraw = millis();
    }

    // Refresh footer every 30s for battery and "ago" timer
    if (screenOn && (millis() - lastFooterRedraw > 30000)) {
        drawFooter();
        lastFooterRedraw = millis();
    }

    delay(20);
}
