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

// ===== STATE VARIABLES - SINGLE SOURCE OF TRUTH =====
bool ovenRunning = false;           // Master state - controls everything
bool ovenHeaterState = false;       // SSR output state (should match ovenRunning when no errors)
bool grinderState = false;
bool conveyorState = false;

// ===== SENSOR VARIABLES =====
float currentTemp = 0.0;
int sensorErrorCount = 0;
const int MAX_SENSOR_ERRORS = 5; 

// ===== THERMOSTAT SETTINGS =====
float lowTemp = 120.0; 
float highTemp = 130.0;

// ===== TIMING VARIABLES =====
unsigned long lastBtnStart = 0, lastBtnGrinder = 0, lastBtnConveyor = 0;
unsigned long ovenRemainingTime = 3600000; // 1 Hour in ms
unsigned long lastTimerTick = 0;
unsigned long lastSSRChange = 0;
unsigned long lastFirebaseSSRUpdate = 0;
unsigned long lastFirebaseRead = 0;
unsigned long lastTempRead = 0;
unsigned long lastUIUpdate = 0;

// ===== INTERVALS =====
const unsigned long MIN_TOGGLE_INTERVAL = 3000; // 3 seconds between SSR changes
const unsigned long debounceDelay = 400;
const unsigned long FIREBASE_SSR_UPDATE_INTERVAL = 3000; // 3 seconds
const unsigned long FIREBASE_READ_INTERVAL = 1500; // 1.5 seconds for app responsiveness
const unsigned long TEMP_READ_INTERVAL = 2000; // 2 seconds
const unsigned long UI_UPDATE_INTERVAL = 2000; // 2 seconds

// ===== TEMPERATURE SMOOTHING =====
float tempReadings[5] = {0};
int tempReadingIndex = 0;
bool tempBufferFilled = false;

// ===== STATE SYNCHRONIZATION =====
bool lastFirebaseHeaterCommand = false;
unsigned long lastFirebaseCommandTime = 0;
const unsigned long FIREBASE_COMMAND_DEBOUNCE = 1000; // 1 second

// ===== CORE STATE MANAGEMENT FUNCTIONS =====

void setOvenState(bool newState, String source) {
    if (ovenRunning != newState) {
        ovenRunning = newState;
        
        Serial.print("Oven state changed by ");
        Serial.print(source);
        Serial.print(": ");
        Serial.println(newState ? "START" : "STOP");
        
        if (newState) {
            ovenRemainingTime = 3600000;
            lastTimerTick = millis();
            updateHeaterState(true, source);
        } else {
            updateHeaterState(false, source);
        }
        
        // Update Firebase to reflect the change
        Firebase.setBool(fb_do, "/heater/status", ovenRunning);
    }
}

void updateHeaterState(bool shouldHeat, String reason) {
    bool newSSRState = shouldHeat && (sensorErrorCount < MAX_SENSOR_ERRORS);
    
    if (ovenHeaterState != newSSRState && millis() - lastSSRChange > MIN_TOGGLE_INTERVAL) {
        ovenHeaterState = newSSRState;
        digitalWrite(SSR_OVEN, ovenHeaterState ? HIGH : LOW);
        lastSSRChange = millis();
        
        Serial.print("SSR state updated (");
        Serial.print(reason);
        Serial.print("): ");
        Serial.println(ovenHeaterState ? "ON" : "OFF");
        
        // Update Firebase SSR state
        if (millis() - lastFirebaseSSRUpdate > FIREBASE_SSR_UPDATE_INTERVAL) {
            Firebase.setBool(fb_do, "/heater/ssr_active", ovenHeaterState);
            lastFirebaseSSRUpdate = millis();
        }
    }
}

// ===== TEMPERATURE READING =====
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
    }
    
    return currentTemp;
}

// ===== SETUP =====
void setup() {
    Serial.begin(115200);
    Serial.println("=== IPOTECH State Synchronization Fix ===");
    
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
    lcd.print("Sync Fix");

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
    
    Serial.println("System Ready - State synchronization enabled");
}

// ===== MAIN LOOP =====
void loop() {
    esp_task_wdt_reset(); 

    // 1. READ TEMPERATURE
    if (millis() - lastTempRead > TEMP_READ_INTERVAL) {
        getSmoothedTemperature();
        lastTempRead = millis();
    }

    // 2. READ FIREBASE COMMANDS
    if (millis() - lastFirebaseRead > FIREBASE_READ_INTERVAL) {
        
        // Read heater command from Firebase
        if (Firebase.getBool(fb_do, "/heater/status")) {
            bool fbCommand = fb_do.boolData();
            
            // Debounce Firebase commands
            if (fbCommand != lastFirebaseHeaterCommand || millis() - lastFirebaseCommandTime > FIREBASE_COMMAND_DEBOUNCE) {
                lastFirebaseHeaterCommand = fbCommand;
                lastFirebaseCommandTime = millis();
                
                // Sync local state with Firebase command
                setOvenState(fbCommand, "Firebase");
            }
        }
        
        // Read other Firebase commands
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
                setOvenState(false, "Timer");
            }
            lastTimerTick = millis();
        }
    } else {
        lastTimerTick = millis(); 
    }

    // 4. THERMOSTAT CONTROL (only if oven is running and no recent manual command)
    if (ovenRunning && (millis() - lastFirebaseCommandTime > 5000)) {
        if (sensorErrorCount >= MAX_SENSOR_ERRORS) {
            updateHeaterState(false, "Sensor Error");
        } else {
            // Temperature-based control
            bool shouldHeat = (currentTemp <= lowTemp);
            updateHeaterState(shouldHeat, "Thermostat");
        }
    }

    // 5. MANUAL BUTTONS
    if (digitalRead(BTN_START) == LOW && millis() - lastBtnStart > debounceDelay) {
        lastBtnStart = millis();
        setOvenState(!ovenRunning, "Button");
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

    // 6. UPDATE LCD AND FIREBASE
    if (millis() - lastUIUpdate > UI_UPDATE_INTERVAL) {
        static int lcdRefreshCounter = 0;
        lcdRefreshCounter++;
        if (lcdRefreshCounter >= 30) {
            lcd.init();
            lcd.backlight();
            lcdRefreshCounter = 0;
        }

        // Update Firebase sensor data
        Firebase.setFloat(fb_do, "/temperature/current", currentTemp);
        Firebase.setInt(fb_do, "/heater/remaining_time", ovenRemainingTime / 1000); 

        // Update Firebase SSR state
        if (millis() - lastFirebaseSSRUpdate > FIREBASE_SSR_UPDATE_INTERVAL) {
            Firebase.setBool(fb_do, "/heater/ssr_active", ovenHeaterState);
            lastFirebaseSSRUpdate = millis();
        }

        // Update LCD
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
        lcd.print(ovenRunning ? "RUNNING" : "STANDBY");
        lcd.print(" ");
        lcd.print(sensorErrorCount >= MAX_SENSOR_ERRORS ? "ERR" : "OK");
        
        lastUIUpdate = millis();
    }
}
