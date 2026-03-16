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

// Hysteresis range (120-130) - WIDER for stability
float lowTemp  = 118.0; 
float highTemp = 132.0;

unsigned long lastBtnStart = 0, lastBtnGrinder = 0, lastBtnConveyor = 0;
unsigned long ovenRemainingTime = 3600000; // 1 Hour in ms
unsigned long lastTimerTick = 0;
unsigned long lastSSRChange = 0;
unsigned long lastFirebaseSSRUpdate = 0;
const unsigned long MIN_TOGGLE_INTERVAL = 10000; // 10 seconds - VERY AGGRESSIVE
const unsigned long debounceDelay = 400;
const unsigned long FIREBASE_SSR_UPDATE_INTERVAL = 5000; // 5 seconds

// NEW: SSR State Lock to prevent rapid changes
bool ssrStateLocked = false;
unsigned long ssrLockTime = 0;
const unsigned long SSR_LOCK_DURATION = 8000; // 8 seconds lock after any change

// NEW: Temperature stability checking
float tempReadings[10] = {0};
int tempReadingIndex = 0;
bool tempBufferFilled = false;
float lastStableTemp = 0.0;
unsigned long tempStableTime = 0;
const float TEMP_STABILITY_THRESHOLD = 1.0; // 1°C variation threshold
const unsigned long TEMP_STABILITY_TIME = 5000; // 5 seconds of stable readings

// NEW: Firebase command debouncing
bool lastFirebaseOvenCommand = false;
unsigned long lastFirebaseCommandTime = 0;
const unsigned long FIREBASE_COMMAND_DEBOUNCE = 3000; // 3 seconds

void lockSSRState() {
    ssrStateLocked = true;
    ssrLockTime = millis();
    Serial.println("SSR state LOCKED for " + String(SSR_LOCK_DURATION/1000) + " seconds");
}

void unlockSSRState() {
    if (ssrStateLocked && millis() - ssrLockTime > SSR_LOCK_DURATION) {
        ssrStateLocked = false;
        Serial.println("SSR state UNLOCKED");
    }
}

bool isSSRStateLocked() {
    return ssrStateLocked && (millis() - ssrLockTime < SSR_LOCK_DURATION);
}

void stopHeater() {
    if (ovenHeaterState && !isSSRStateLocked()) {
        ovenHeaterState = false;
        digitalWrite(SSR_OVEN, LOW);
        lastSSRChange = millis();
        lockSSRState(); // Lock state after change
        
        if (millis() - lastFirebaseSSRUpdate > FIREBASE_SSR_UPDATE_INTERVAL) {
            Firebase.setBool(fb_do, "/heater/ssr_active", false);
            lastFirebaseSSRUpdate = millis();
        }
        Serial.println("HEATER STOPPED - State locked");
    }
}

void startHeater() {
    if (!ovenHeaterState && !isSSRStateLocked()) {
        ovenHeaterState = true;
        digitalWrite(SSR_OVEN, HIGH);
        lastSSRChange = millis();
        lockSSRState(); // Lock state after change
        
        if (millis() - lastFirebaseSSRUpdate > FIREBASE_SSR_UPDATE_INTERVAL) {
            Firebase.setBool(fb_do, "/heater/ssr_active", true);
            lastFirebaseSSRUpdate = millis();
        }
        Serial.println("HEATER STARTED - State locked");
    }
}

float getStableTemperature() {
    // Add new reading to buffer
    float newReading = thermocouple.readCelsius();
    tempReadings[tempReadingIndex] = newReading;
    tempReadingIndex = (tempReadingIndex + 1) % 10;
    
    if (!tempBufferFilled && tempReadingIndex == 0) {
        tempBufferFilled = true;
    }
    
    // Calculate average of valid readings
    float sum = 0;
    int validReadings = 0;
    float minVal = 999, maxVal = -999;
    
    for (int i = 0; i < 10; i++) {
        if (!isnan(tempReadings[i]) && tempReadings[i] > 0.0) {
            sum += tempReadings[i];
            validReadings++;
            if (tempReadings[i] < minVal) minVal = tempReadings[i];
            if (tempReadings[i] > maxVal) maxVal = tempReadings[i];
        }
    }
    
    float avgTemp = validReadings > 0 ? sum / validReadings : NAN;
    
    // Check if temperature is stable
    if (validReadings >= 5 && (maxVal - minVal) < TEMP_STABILITY_THRESHOLD) {
        if (fabs(avgTemp - lastStableTemp) < TEMP_STABILITY_THRESHOLD) {
            tempStableTime = millis();
        }
        lastStableTemp = avgTemp;
        return avgTemp;
    }
    
    return avgTemp; // Return average even if not stable
}

bool isTemperatureStable() {
    return millis() - tempStableTime > TEMP_STABILITY_TIME && tempBufferFilled;
}

void setup() {
    Serial.begin(115200);
    Serial.println("IPOTECH Anti-Flicker System Starting...");
    
    // Watchdog Timer Setup
    esp_task_wdt_config_t wdt_config = {
        .timeout_ms = 20000,
        .idle_core_mask = 0,
        .trigger_panic = true
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
    lcd.print("Anti-Flicker");

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
    
    delay(1000); // Let Firebase settle
    lcd.clear();
    
    Serial.println("Anti-Flicker System Ready");
}

void loop() {
    esp_task_wdt_reset(); 
    unlockSSRState(); // Check if SSR lock should be released

    // 1. SENSOR READING (Every 3s - even slower for stability)
    static unsigned long lastTempRead = 0;
    if (millis() - lastTempRead > 3000) {
        float stableTemp = getStableTemperature();
        
        if(isnan(stableTemp) || stableTemp <= 0.0) {
            sensorErrorCount++;
            Serial.println("MAX6675 error - count: " + String(sensorErrorCount));
        } else {
            sensorErrorCount = 0;
            currentTemp = stableTemp;
            Serial.print("Stable Temp: "); Serial.print(currentTemp); Serial.println("°C");
            Serial.print("Temp stable: "); Serial.println(isTemperatureStable() ? "YES" : "NO");
        }
        lastTempRead = millis();
    }

    // 2. FIREBASE COMMANDS (Every 3s with debouncing)
    static unsigned long lastFirebaseRead = 0;
    if (millis() - lastFirebaseRead > 3000) {
        if (Firebase.getBool(fb_do, "/heater/status")) {
            bool fbVal = fb_do.boolData();
            
            // Debounce Firebase commands
            if (fbVal != lastFirebaseOvenCommand || millis() - lastFirebaseCommandTime > FIREBASE_COMMAND_DEBOUNCE) {
                if (fbVal != ovenRunning) {
                    ovenRunning = fbVal;
                    lastFirebaseOvenCommand = fbVal;
                    lastFirebaseCommandTime = millis();
                    
                    if (ovenRunning) {
                        ovenRemainingTime = 3600000;
                        lastTimerTick = millis();
                        Serial.println("Oven STARTED via Firebase (debounced)");
                    } else {
                        stopHeater();
                        Serial.println("Oven STOPPED via Firebase (debounced)");
                    }
                }
            }
        }
        
        // Other Firebase commands (without debouncing)
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

    // 4. AGGRESSIVE THERMOSTAT WITH STABILITY CHECKING
    if (ovenRunning && !isSSRStateLocked()) {
        if (sensorErrorCount >= MAX_SENSOR_ERRORS) {
            if (ovenHeaterState) {
                Serial.println("Sensor error - stopping heater");
                stopHeater(); 
            }
        } else if (isTemperatureStable() && millis() - lastSSRChange > MIN_TOGGLE_INTERVAL) {
            // Only make decisions when temperature is stable
            if (currentTemp <= lowTemp && !ovenHeaterState) {
                Serial.println("Temp stable and LOW - starting heater");
                startHeater(); 
            } else if (currentTemp >= highTemp && ovenHeaterState) {
                Serial.println("Temp stable and HIGH - stopping heater");
                stopHeater(); 
            }
        }
    }

    // 5. MANUAL BUTTONS (with SSR lock check)
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

    // 6. LCD & FIREBASE SYNC (Every 4s)
    static unsigned long lastUIUpdate = 0;
    static int lcdRefreshCounter = 0;
    if (millis() - lastUIUpdate > 4000) {
        
        lcdRefreshCounter++;
        if (lcdRefreshCounter >= 15) {
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
        lcd.print(isSSRStateLocked() ? "L" : " "); // Show lock status
        lcd.setCursor(9, 1); 
        lcd.print("Cnv: "); lcd.print(conveyorState ? "ON " : "OFF");
        
        lcd.setCursor(0, 2); 
        lcd.print("Plv: "); lcd.print(grinderState ? "ON " : "OFF");
        lcd.setCursor(9, 2); 
        lcd.print("Cnt: "); lcd.print(countdown);
        
        lcd.setCursor(0, 3);
        lcd.print(isTemperatureStable() ? "STABLE " : "WAITING ");
        lcd.print(ovenRunning ? "RUNNING" : "STANDBY");
        
        lastUIUpdate = millis();
    }
}
