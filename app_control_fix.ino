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
unsigned long lastFirebaseSSRUpdate = 0;
const unsigned long MIN_TOGGLE_INTERVAL = 3000; // REDUCED: 3 seconds for app responsiveness
const unsigned long debounceDelay = 400;
const unsigned long FIREBASE_SSR_UPDATE_INTERVAL = 3000; // 3 seconds

// REMOVED: Aggressive locking that was blocking app control
// bool ssrStateLocked = false;
// unsigned long ssrLockTime = 0;
// const unsigned long SSR_LOCK_DURATION = 6000;

// Firebase command tracking
bool lastFirebaseOvenCommand = false;
unsigned long lastFirebaseCommandTime = 0;
const unsigned long FIREBASE_COMMAND_DEBOUNCE = 1000; // REDUCED: 1 second for faster response

// Temperature smoothing (simplified)
float tempReadings[5] = {0};
int tempReadingIndex = 0;
bool tempBufferFilled = false;

// REMOVED: Complex locking functions that blocked app control
void stopHeater() {
    if (ovenHeaterState) {
        ovenHeaterState = false;
        digitalWrite(SSR_OVEN, LOW);
        lastSSRChange = millis();
        
        if (millis() - lastFirebaseSSRUpdate > FIREBASE_SSR_UPDATE_INTERVAL) {
            Firebase.setBool(fb_do, "/heater/ssr_active", false);
            lastFirebaseSSRUpdate = millis();
        }
        Serial.println("HEATER STOPPED");
    }
}

void startHeater() {
    if (!ovenHeaterState) {
        ovenHeaterState = true;
        digitalWrite(SSR_OVEN, HIGH);
        lastSSRChange = millis();
        
        if (millis() - lastFirebaseSSRUpdate > FIREBASE_SSR_UPDATE_INTERVAL) {
            Firebase.setBool(fb_do, "/heater/ssr_active", true);
            lastFirebaseSSRUpdate = millis();
        }
        Serial.println("HEATER STARTED");
    }
}

// Simplified temperature reading
float getSmoothedTemperature() {
    float newTemp = thermocouple.readCelsius();
    
    if (isnan(newTemp) || newTemp <= 0.0) {
        sensorErrorCount++;
        return currentTemp;
    }
    
    tempReadings[tempReadingIndex] = newTemp;
    tempReadingIndex = (tempReadingIndex + 1) % 5;
    
    if (!tempBufferFilled && tempReadingIndex == 0) {
        tempBufferFilled = true;
    }
    
    float sum = 0;
    int validCount = 0;
    
    for (int i = 0; i < 5; i++) {
        if (!isnan(tempReadings[i]) && tempReadings[i] > 0) {
            sum += tempReadings[i];
            validCount++;
        }
    }
    
    if (validCount >= 3) {
        currentTemp = sum / validCount;
        sensorErrorCount = 0;
        Serial.print("Temp: "); Serial.print(currentTemp); Serial.println("°C");
    }
    
    return currentTemp;
}

void setup() {
    Serial.begin(115200);
    Serial.println("=== IPOTECH App Control Fix ===");
    
    // Watchdog Timer Setup
    esp_task_wdt_config_t wdt_config = {
        .timeout_ms = 20000,
        .idle_core_mask = 0,
        .trigger_panic = true
    };
    esp_task_wdt_init(&wdt_config); 
    esp_task_wdt_add(NULL);      

    // Initialize pins
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
    delay(500);

    Wire.begin(21, 22);
    lcd.init();
    lcd.backlight();
    
    lcd.setCursor(1, 0); 
    lcd.print("IPOTech System");
    lcd.setCursor(3, 1); 
    lcd.print("App Fix");

    WiFi.begin(ssid, password);
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
        esp_task_wdt_reset(); 
    }
    Serial.println("\nWiFi Connected!");

    config.host = FIREBASE_HOST;
    config.signer.tokens.legacy_token = FIREBASE_AUTH;
    Firebase.begin(&config, &auth);
    Firebase.reconnectWiFi(true);
    
    delay(1000);
    lcd.clear();
    
    Serial.println("System Ready - App Control Enabled");
}

void loop() {
    esp_task_wdt_reset(); 

    // 1. TEMPERATURE READING (Every 2s)
    static unsigned long lastTempRead = 0;
    if (millis() - lastTempRead > 2000) {
        getSmoothedTemperature();
        lastTempRead = millis();
    }

    // 2. FIREBASE COMMANDS (Every 1.5s - faster for app responsiveness)
    static unsigned long lastFirebaseRead = 0;
    if (millis() - lastFirebaseRead > 1500) {
        
        // HEATER COMMAND - IMMEDIATE RESPONSE FOR APP CONTROL
        if (Firebase.getBool(fb_do, "/heater/status")) {
            bool fbVal = fb_do.boolData();
            
            // Reduced debouncing for faster app response
            if (fbVal != lastFirebaseOvenCommand || millis() - lastFirebaseCommandTime > FIREBASE_COMMAND_DEBOUNCE) {
                if (fbVal != ovenRunning) {
                    ovenRunning = fbVal;
                    lastFirebaseOvenCommand = fbVal;
                    lastFirebaseCommandTime = millis();
                    
                    Serial.print("Firebase heater command: ");
                    Serial.println(fbVal ? "START" : "STOP");
                    
                    if (ovenRunning) {
                        ovenRemainingTime = 3600000;
                        lastTimerTick = millis();
                        // IMMEDIATELY start heater for app control
                        startHeater();
                    } else {
                        // IMMEDIATELY stop heater for app control
                        stopHeater();
                    }
                }
            }
        }
        
        // Other Firebase commands
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

    // 4. SIMPLIFIED THERMOSTAT (only if oven is running and not manually controlled)
    if (ovenRunning && millis() - lastFirebaseCommandTime > 5000) { // Only thermostat if no recent manual command
        if (sensorErrorCount >= MAX_SENSOR_ERRORS) {
            if (ovenHeaterState) {
                Serial.println("Sensor error - stopping heater");
                stopHeater(); 
            }
        } else if (millis() - lastSSRChange > MIN_TOGGLE_INTERVAL) {
            if (currentTemp <= lowTemp && !ovenHeaterState) {
                Serial.println("Temp low - starting heater");
                startHeater(); 
            } else if (currentTemp >= highTemp && ovenHeaterState) {
                Serial.println("Temp high - stopping heater");
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
            startHeater();
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

    // 6. LCD & FIREBASE SYNC (Every 2s)
    static unsigned long lastUIUpdate = 0;
    static int lcdRefreshCounter = 0;
    if (millis() - lastUIUpdate > 2000) {
        
        lcdRefreshCounter++;
        if (lcdRefreshCounter >= 30) {
            lcd.init();
            lcd.backlight();
            lcdRefreshCounter = 0;
        }

        Firebase.setFloat(fb_do, "/temperature/current", currentTemp);
        Firebase.setInt(fb_do, "/heater/remaining_time", ovenRemainingTime / 1000); 

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
        lcd.print("APP READY       ");
        lcd.print(ovenRunning ? "RUN" : "STB");
        
        lastUIUpdate = millis();
    }
}
