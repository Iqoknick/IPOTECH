#include <max6675.h>
#include <WiFi.h>
#include <ArduinoJson.h>
#include <FirebaseESP32.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <esp_task_wdt.h> 

// ===== PIN DEFINITIONS =====
const int thermoSO  = 19;
const int thermoCS  = 5;
const int thermoSCK = 18;
const int SSR_OVEN        = 26;
const int RELAY_GRINDER   = 32;
const int RELAY_CONVEYOR  = 33;
const int BTN_START     = 23;
const int BTN_GRINDER   = 25;
const int BTN_CONVEYOR  = 4; // GPIO 4

// ===== OBJECTS =====
MAX6675 thermocouple(thermoSCK, thermoCS, thermoSO);
FirebaseData fb_do; 
FirebaseConfig config;
FirebaseAuth auth;
LiquidCrystal_I2C lcd(0x27, 16, 4);

// ===== CONFIG =====
const char* ssid = "TP-Link_2C1F";
const char* password = "23046054";
#define FIREBASE_HOST "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
#define FIREBASE_AUTH "GFI2mhoOx6kuKsPvsaJshfNQFRMLwfZRQigGeWiW"

// ===== VARIABLES =====
bool ovenRunning      = false; 
bool ovenHeaterState  = false; 
bool grinderState     = false;
bool conveyorState    = false;

float currentTemp     = 0.0;
int sensorErrorCount  = 0;
const int MAX_SENSOR_ERRORS = 5; 

// Hysteresis range (120-130)
float lowTemp  = 120.0; 
float highTemp = 130.0;

unsigned long lastBtnStart = 0, lastBtnGrinder = 0, lastBtnConveyor = 0;
unsigned long ovenRemainingTime = 3600000; // 1 Hour in ms
unsigned long lastTimerTick = 0;
unsigned long lastSSRChange = 0;
unsigned long lastFirebaseSSRUpdate = 0; // NEW: Rate limit Firebase SSR updates
const unsigned long MIN_TOGGLE_INTERVAL = 5000; // INCREASED: 5 seconds to prevent flickering
const unsigned long debounceDelay = 400;
const unsigned long FIREBASE_SSR_UPDATE_INTERVAL = 3000; // NEW: Update Firebase every 3 seconds max

// NEW: Temperature smoothing
float tempReadings[5] = {0};
int tempReadingIndex = 0;
bool tempBufferFilled = false;

void stopHeater() {
    if (ovenHeaterState) { // Only update if actually changing state
        ovenHeaterState = false;
        digitalWrite(SSR_OVEN, LOW);
        lastSSRChange = millis();
        // Rate limit Firebase updates
        if (millis() - lastFirebaseSSRUpdate > FIREBASE_SSR_UPDATE_INTERVAL) {
            Firebase.setBool(fb_do, "/heater/ssr_active", false);
            lastFirebaseSSRUpdate = millis();
        }
    }
}

void startHeater() {
    if (!ovenHeaterState) { // Only update if actually changing state
        ovenHeaterState = true;
        digitalWrite(SSR_OVEN, HIGH);
        lastSSRChange = millis();
        // Rate limit Firebase updates
        if (millis() - lastFirebaseSSRUpdate > FIREBASE_SSR_UPDATE_INTERVAL) {
            Firebase.setBool(fb_do, "/heater/ssr_active", true);
            lastFirebaseSSRUpdate = millis();
        }
    }
}

float getSmoothedTemperature() {
    // Add new reading to buffer
    tempReadings[tempReadingIndex] = thermocouple.readCelsius();
    tempReadingIndex = (tempReadingIndex + 1) % 5;
    
    if (!tempBufferFilled && tempReadingIndex == 0) {
        tempBufferFilled = true;
    }
    
    // Calculate average of valid readings
    float sum = 0;
    int validReadings = 0;
    
    for (int i = 0; i < 5; i++) {
        if (!isnan(tempReadings[i]) && tempReadings[i] > 0.0) {
            sum += tempReadings[i];
            validReadings++;
        }
    }
    
    return validReadings > 0 ? sum / validReadings : NAN;
}

void setup() {
    Serial.begin(115200);
    
    // Watchdog Timer Setup (ESP32 Core 3.0+ compatible)
    esp_task_wdt_config_t wdt_config = {
        .timeout_ms = 20000,   // 20 seconds
        .idle_core_mask = 0,   // Monitor all cores
        .trigger_panic = true  // Reboot if frozen
    };
    esp_task_wdt_init(&wdt_config); 
    esp_task_wdt_add(NULL);      

    pinMode(SSR_OVEN, OUTPUT); 
    pinMode(RELAY_GRINDER, OUTPUT); 
    pinMode(RELAY_CONVEYOR, OUTPUT);
    digitalWrite(SSR_OVEN, LOW); 
    digitalWrite(RELAY_GRINDER, LOW); 
    digitalWrite(RELAY_CONVEYOR, LOW);
    
    pinMode(BTN_START, INPUT_PULLUP); 
    pinMode(BTN_GRINDER, INPUT_PULLUP); 
    pinMode(BTN_CONVEYOR, INPUT_PULLUP);

    pinMode(thermoCS, OUTPUT);
    digitalWrite(thermoCS, HIGH); 

    Wire.begin(21, 22);
    lcd.init();
    lcd.backlight();
    
    lcd.setCursor(1, 0); 
    lcd.print("IPOTech System");
    lcd.setCursor(3, 1); 
    lcd.print("Booting...");

    WiFi.begin(ssid, password);
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
        esp_task_wdt_reset(); 
    }

    config.host = FIREBASE_HOST;
    config.signer.tokens.legacy_token = FIREBASE_AUTH;
    Firebase.begin(&config, &auth);
    Firebase.reconnectWiFi(true);
    lcd.clear();
    
    Serial.println("System Ready - MAX6675 initialized");
}

void loop() {
    esp_task_wdt_reset(); 

    // 1. SENSOR READING (Every 2s - increased for MAX6675 stability)
    static unsigned long lastTempRead = 0;
    if (millis() - lastTempRead > 2000) {
        float smoothedTemp = getSmoothedTemperature();
        
        if(isnan(smoothedTemp) || smoothedTemp <= 0.0) {
            sensorErrorCount++;
            Serial.println("MAX6675 read error - count: " + String(sensorErrorCount));
        } else {
            sensorErrorCount = 0;
            currentTemp = smoothedTemp;
            Serial.print("Temperature: "); Serial.print(currentTemp); Serial.println("°C");
        }
        lastTempRead = millis();
    }

    // 2. FIREBASE COMMANDS (Every 2s - reduced frequency)
    static unsigned long lastFirebaseRead = 0;
    if (millis() - lastFirebaseRead > 2000) {
        if (Firebase.getBool(fb_do, "/heater/status")) {
            bool fbVal = fb_do.boolData();
            if (fbVal != ovenRunning) {
                ovenRunning = fbVal;
                if (ovenRunning) {
                    ovenRemainingTime = 3600000;
                    lastTimerTick = millis();
                    Serial.println("Oven started via Firebase");
                } else {
                    stopHeater();
                    Serial.println("Oven stopped via Firebase");
                }
            }
        }
        if (Firebase.getBool(fb_do, "/pulverizer/status")) {
            grinderState = fb_do.boolData();
            digitalWrite(RELAY_GRINDER, grinderState ? HIGH : LOW);
        }
        if (Firebase.getBool(fb_do, "/conveyor/status")) {
            conveyorState = fb_do.boolData();
            digitalWrite(RELAY_CONVEYOR, conveyorState ? HIGH : LOW);
        }
        lastFirebaseRead = millis();
    }

    // 3. TIMER LOGIC
    if (ovenRunning && sensorErrorCount < MAX_SENSOR_ERRORS) {
        if (millis() - lastTimerTick >= 1000) {
            if (ovenRemainingTime >= 1000) ovenRemainingTime -= 1000;
            else {
                ovenRunning = false;
                stopHeater();
                Firebase.setBool(fb_do, "/heater/status", false);
                Serial.println("Oven timer expired");
            }
            lastTimerTick = millis();
        }
    } else {
        lastTimerTick = millis(); 
    }

    // 4. IMPROVED THERMOSTAT & ANTI-FLICKER LOGIC
    if (ovenRunning) {
        if (sensorErrorCount >= MAX_SENSOR_ERRORS) {
            if (ovenHeaterState) {
                Serial.println("Sensor error - stopping heater");
                stopHeater(); 
            }
        } else if (millis() - lastSSRChange > MIN_TOGGLE_INTERVAL) {
            // More conservative hysteresis to prevent flickering
            if (currentTemp <= (lowTemp - 2.0) && !ovenHeaterState) {
                Serial.println("Temperature below threshold - starting heater");
                startHeater(); 
            } else if (currentTemp >= (highTemp + 2.0) && ovenHeaterState) {
                Serial.println("Temperature above threshold - stopping heater");
                stopHeater(); 
            }
        }
    }

    // 5. MANUAL BUTTONS
    if (digitalRead(BTN_START) == LOW && millis() - lastBtnStart > debounceDelay) {
        ovenRunning = !ovenRunning; 
        lastBtnStart = millis();
        if (ovenRunning) { 
            ovenRemainingTime = 3600000; 
            lastTimerTick = millis(); 
            Serial.println("Oven started via button");
        } else {
            stopHeater();
            Serial.println("Oven stopped via button");
        }
        Firebase.setBool(fb_do, "/heater/status", ovenRunning);
    }
    if (digitalRead(BTN_GRINDER) == LOW && millis() - lastBtnGrinder > debounceDelay) {
        grinderState = !grinderState; 
        lastBtnGrinder = millis();
        digitalWrite(RELAY_GRINDER, grinderState ? HIGH : LOW);
        Firebase.setBool(fb_do, "/pulverizer/status", grinderState);
    }
    if (digitalRead(BTN_CONVEYOR) == LOW && millis() - lastBtnConveyor > debounceDelay) {
        conveyorState = !conveyorState; 
        lastBtnConveyor = millis();
        digitalWrite(RELAY_CONVEYOR, conveyorState ? HIGH : LOW);
        Firebase.setBool(fb_do, "/conveyor/status", conveyorState);
    }

    // 6. LCD & FIREBASE SYNC (Every 3s - reduced frequency)
    static unsigned long lastUIUpdate = 0;
    static int lcdRefreshCounter = 0;
    if (millis() - lastUIUpdate > 3000) {
        
        // LCD noise fix
        lcdRefreshCounter++;
        if (lcdRefreshCounter >= 20) { // Reduced frequency
            lcd.init();
            lcd.backlight();
            lcdRefreshCounter = 0;
        }

        // Update Firebase sensor data
        Firebase.setFloat(fb_do, "/temperature/current", currentTemp);
        Firebase.setInt(fb_do, "/heater/remaining_time", ovenRemainingTime / 1000); 

        // Update Firebase SSR state periodically
        if (millis() - lastFirebaseSSRUpdate > FIREBASE_SSR_UPDATE_INTERVAL) {
            Firebase.setBool(fb_do, "/heater/ssr_active", ovenHeaterState);
            lastFirebaseSSRUpdate = millis();
        }

        int min = ovenRemainingTime / 60000;
        int sec = (ovenRemainingTime % 60000) / 1000;
        String countdown = String(min) + ":" + (sec < 10 ? "0" : "") + String(sec);
        
        lcd.setCursor(0, 0); 
        if (sensorErrorCount >= MAX_SENSOR_ERRORS) {
            lcd.print("Temp: SENSOR ERR");
        } else {
            lcd.print("Temp: "); lcd.print(currentTemp, 1); lcd.print(" C     "); 
        }
        
        lcd.setCursor(0, 1); 
        lcd.print("Htr: "); lcd.print(ovenHeaterState ? "ON " : "OFF");
        lcd.setCursor(9, 1); 
        lcd.print("Cnv: "); lcd.print(conveyorState ? "ON " : "OFF");
        
        lcd.setCursor(0, 2); 
        lcd.print("Plv: "); lcd.print(grinderState ? "ON " : "OFF");
        lcd.setCursor(9, 2); 
        lcd.print("Cnt: "); lcd.print(countdown);
        
        lcd.setCursor(0, 3);
        lcd.print(ovenRunning ? "RUNNING...      " : "STANDBY         ");
        
        lastUIUpdate = millis();
    }
}
