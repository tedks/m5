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
#define MAX_QUOTAS 6

struct Quota {
    char name[16];
    float used;
    float limit;
    char unit[8];
};

static Quota quotas[MAX_QUOTAS];
static int numQuotas = 0;
static bool bleConnected = false;
static bool needsRedraw = true;
static unsigned long lastUpdateTime = 0;
static unsigned long lastFooterRedraw = 0;
static bool screenOn = true;

static BLEServer* pServer = nullptr;

// --- Parsing ---
// Protocol: newline-separated lines of "Name:used:limit:unit"
// Example: "Claude:17:100:%\nCodex:42:100:%\nActions:465:3000:min"

static void parseQuotaData(const char* data, size_t len) {
    char buf[512];
    size_t n = (len < sizeof(buf) - 1) ? len : sizeof(buf) - 1;
    memcpy(buf, data, n);
    buf[n] = '\0';

    numQuotas = 0;
    char* saveptr = nullptr;
    char* line = strtok_r(buf, "\n", &saveptr);

    while (line && numQuotas < MAX_QUOTAS) {
        Quota* q = &quotas[numQuotas];
        if (sscanf(line, "%15[^:]:%f:%f:%7s",
                   q->name, &q->used, &q->limit, q->unit) == 4) {
            numQuotas++;
        }
        line = strtok_r(nullptr, "\n", &saveptr);
    }
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
    int barX = 62;
    int barW = 120;
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
        int rowH = (SCREEN_H - 44) / numQuotas;
        if (rowH > 28) rowH = 28;
        bool compact = (numQuotas > 3);
        for (int i = 0; i < numQuotas; i++) {
            if (compact) {
                drawQuotaRowCompact(i, 24 + i * rowH, rowH);
            } else {
                drawQuotaRow(i, 24 + i * rowH);
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
        if (!val.empty()) {
            parseQuotaData(val.c_str(), val.length());
            lastUpdateTime = millis();
            needsRedraw = true;
        }
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

    // Button A (front): short press = wake/toggle screen, long press = force redraw
    if (M5.BtnA.wasReleasefor(600)) {
        // Long press: force redraw
        needsRedraw = true;
    } else if (M5.BtnA.wasPressed()) {
        // Short press: toggle screen
        screenOn = !screenOn;
        if (screenOn) {
            M5.Axp.ScreenBreath(15);  // max brightness
            needsRedraw = true;
        } else {
            M5.Axp.ScreenBreath(0);
        }
    }

    // Button B (side): cycle brightness
    if (M5.BtnB.wasPressed()) {
        static int brightness = 15;
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
