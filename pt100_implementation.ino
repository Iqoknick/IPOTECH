/*
 * PT100 Temperature Sensor Implementation for IPOTECH
 * Replaces problematic MAX6675 thermocouple with stable PT100
 */

#include <WiFi.h>
#include <ArduinoJson.h>
#include <FirebaseESP32.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <esp_task_wdt.h>
#include <HX711.h> // PT100 amplifier library

// ===== PIN DEFINITIONS =====
const int HX711_DOUT = 19;  // HX711 data pin
const int HX711_CLK  = 18;  // HX711 clock pin
const int SSR_OVEN        = 26;
const int RELAY_GRINDER   = 32;
const int RELAY_CONVEYOR  = 33;
const int BTN_START     = 23;
const int BTN_GRINDER   = 25;
const int BTN_CONVEYOR  = 4;

// ===== OBJECTS =====
HX711 scale; // PT100 amplifier
FirebaseData fb_do; 
FirebaseConfig config;
FirebaseAuth auth;
LiquidCrystal_I2C lcd(0x27, 16, 4);

// ===== CONFIG =====
const char* ssid = "TP-Link_2C1F";
const char* password = "23046054";
#define FIREBASE_HOST "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
#define FIREBASE_AUTH "GFI2mhoOx6kuKsPvsaJshfNQFRMLwfZRQigGeWiW"

// PT100 CALIBRATION CONSTANTS
const float R0 = 100.0;           // PT100 resistance at 0°C
const float A = 3.9083e-3;        // Callendar-Van Dusen coefficient A
const float B = -5.775e-7;        // Callendar-Van Dusen coefficient B
const float CALIBRATION_FACTOR = 1000.0; // Adjust based on your HX711 setup

// ===== STATE VARIABLES =====
bool ovenRunning = false;           
bool ovenHeaterState = false;       
bool grinderState = false;
bool conveyorState = false;

// ===== SENSOR VARIABLES =====
float currentTemp = 0.0;
int sensorErrorCount = 0;
const int MAX_SENSOR_ERRORS = 3; // PT100 is more reliable, reduce threshold

// ===== THERMOSTAT SETTINGS (more precise with PT100) =====
float lowTemp = 120.0; 
float highTemp = 130.0;

// ===== TIMING VARIABLES =====
unsigned long lastBtnStart = 0, lastBtnGrinder = 0, lastBtnConveyor = 0;
unsigned long ovenRemainingTime = 3600000;
unsigned long lastTimerTick = 0;
unsigned long lastSSRChange = 0;
unsigned long lastFirebaseSSRUpdate = 0;
unsigned long lastFirebaseRead = 0;
unsigned long lastTempRead = 0;
unsigned long lastUIUpdate = 0;

// ===== INTERVALS =====
const unsigned long MIN_TOGGLE_INTERVAL = 5000; // Can be shorter with stable sensor
const unsigned long debounceDelay = 400;
const unsigned long FIREBASE_SSR_UPDATE_INTERVAL = 3000;
const unsigned long FIREBASE_READ_INTERVAL = 1500;
const unsigned long TEMP_READ_INTERVAL = 1000; // Faster reading with PT100
const unsigned long UI_UPDATE_INTERVAL = 2000;

// ===== TEMPERATURE SMOOTHING =====
float tempReadings[10] = {0}; // More smoothing for precision
int tempReadingIndex = 0;
bool tempBufferFilled = false;

// ===== STATE SYNCHRONIZATION =====
bool lastFirebaseHeaterCommand = false;
unsigned long lastFirebaseCommandTime = 0;
const unsigned long FIREBASE_COMMAND_DEBOUNCE = 1000;

// ===== PT100 TEMPERATURE CONVERSION =====
float convertPT100toTemperature(float resistance) {
    // Callendar-Van Dusen equation for PT100
    // For temperatures > 0°C: R(t) = R0 * (1 + A*t + B*t²)
    
    if (resistance < 20 || resistance > 400) {
        return NAN; // Invalid resistance range
    }
    
    // Solve quadratic equation: B*t² + A*t + (1 - R/R0) = 0
    float c = 1.0 - (resistance / R0);
    float discriminant = A*A - 4*B*c;
    
    if (discriminant < 0) {
        return NAN; // No real solution
    }
    
    float temp = (-A + sqrt(discriminant)) / (2*B);
    
    // Validate temperature range
    if (temp < -50 || temp > 400) {
        return NAN;
    }
    
    return temp;
}

// ===== PT100 READING FUNCTION =====
float readPT100Temperature() {
    // Read from HX711
    if (scale.is_ready()) {
        float rawReading = scale.read();
        float resistance = rawReading / CALIBRATION_FACTOR;
        
        float temperature = convertPT100toTemperature(resistance);
        
        if (!isnan(temperature)) {
            Serial.print("PT100 Raw: ");
            Serial.print(rawReading);
            Serial.print(", Resistance: ");
            Serial.print(resistance);
            Serial.print("Ω, Temp: ");
            Serial.print(temperature, 2);
            Serial.println("°C");
            
            return temperature;
        }
    }
    
    return NAN;
}

// ===== SMOOTHED TEMPERATURE =====
float getSmoothedTemperature() {
    float newTemp = readPT100Temperature();
    
    if (isnan(newTemp)) {
        sensorErrorCount++;
        Serial.println("PT100 read error - count: " + String(sensorErrorCount));
        return currentTemp;
    }
    
    // Add to smoothing buffer
    tempReadings[tempReadingIndex] = newTemp;
    tempReadingIndex = (tempReadingIndex + 1) % 10;
    
    if (!tempBufferFilled && tempReadingIndex == 0) {
        tempBufferFilled = true;
    }
    
    // Calculate average with outlier rejection
    float sum = 0;
    int validCount = 0;
    float minVal = 999, maxVal = -999;
    
    for (int i = 0; i < 10; i++) {
        if (!isnan(tempReadings[i]) && tempReadings[i] > -50 && tempReadings[i] < 400) {
            sum += tempReadings[i];
            validCount++;
            if (tempReadings[i] < minVal) minVal = tempReadings[i];
            if (tempReadings[i] > maxVal) maxVal = tempReadings[i];
        }
    }
    
    // Only use readings if variation is reasonable (PT100 should be stable)
    if (validCount >= 6 && (maxVal - minVal) < 2.0) { // Max 2°C variation
        currentTemp = sum / validCount;
        sensorErrorCount = 0;
    } else if (validCount >= 6) {
        Serial.println("PT100 reading variation too high: " + String(maxVal - minVal) + "°C");
    }
    
    return currentTemp;
}

// ===== STATE MANAGEMENT =====
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
        
        if (millis() - lastFirebaseSSRUpdate > FIREBASE_SSR_UPDATE_INTERVAL) {
            Firebase.setBool(fb_do, "/heater/ssr_active", ovenHeaterState);
            lastFirebaseSSRUpdate = millis();
        }
    }
}

// ===== SETUP =====
void setup() {
    Serial.begin(115200);
    Serial.println("=== IPOTECH PT100 Version ===");
    
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

    // Initialize PT100/HX711
    scale.begin(HX711_DOUT, HX711_CLK);
    scale.set_scale(CALIBRATION_FACTOR);
    scale.tare();
    
    Wire.begin(21, 22);
    lcd.init();
    lcd.backlight();
    
    lcd.setCursor(1, 0); 
    lcd.print("IPOTech System");
    lcd.setCursor(3, 1); 
    lcd.print("PT100 Sensor");

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
    
    // Initial PT100 readings
    for (int i = 0; i < 5; i++) {
        getSmoothedTemperature();
        delay(500);
    }
    
    Serial.println("System Ready - PT100 temperature: " + String(currentTemp, 2) + "°C");
}

// ===== MAIN LOOP =====
void loop() {
    esp_task_wdt_reset(); 

    // 1. READ PT100 TEMPERATURE (faster and more reliable)
    if (millis() - lastTempRead > TEMP_READ_INTERVAL) {
        getSmoothedTemperature();
        lastTempRead = millis();
    }

    // 2. READ FIREBASE COMMANDS
    if (millis() - lastFirebaseRead > FIREBASE_READ_INTERVAL) {
        
        if (Firebase.getBool(fb_do, "/heater/status")) {
            bool fbCommand = fb_do.boolData();
            
            if (fbCommand != lastFirebaseHeaterCommand || millis() - lastFirebaseCommandTime > FIREBASE_COMMAND_DEBOUNCE) {
                lastFirebaseHeaterCommand = fbCommand;
                lastFirebaseCommandTime = millis();
                
                setOvenState(fbCommand, "Firebase");
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
                setOvenState(false, "Timer");
            }
            lastTimerTick = millis();
        }
    } else {
        lastTimerTick = millis(); 
    }

    // 4. PRECISE THERMOSTAT CONTROL (PT100 enables better control)
    if (ovenRunning && (millis() - lastFirebaseCommandTime > 5000)) {
        if (sensorErrorCount >= MAX_SENSOR_ERRORS) {
            updateHeaterState(false, "Sensor Error");
        } else {
            // More precise control with PT100 accuracy
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
            lcd.print("Temp: PT100 ERR");
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
        lcd.print("PT100 READY     ");
        lcd.print(ovenRunning ? "RUN" : "STB");
        
        lastUIUpdate = millis();
    }
}
