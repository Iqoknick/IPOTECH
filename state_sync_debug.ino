/*
 * State Synchronization Debugging Code
 * Identifies why app, LCD, and SSR show different heater states
 */

#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>

// ===== WIFI =====
#define WIFI_SSID "TP-Link_2C1F"
#define WIFI_PASSWORD "23046054"

// ===== FIREBASE =====
#define API_KEY "GFI2mhoOx6kuKsPvsaJshfNQFRMLwfZRQigGeWiW"
#define DATABASE_URL "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
#define FIREBASE_AUTH "GFI2mhoOx6kuKsPvsaJshfNQFRMLwfZRQigGeWiW"

// Hardware
const int SSR_OVEN = 26;
const int BTN_START = 13;    // Start/Stop oven (HEATER)
const int BTN_GRINDER = 4;    // Toggle grinder  
const int BTN_CONVEYOR = 14; // Toggle conveyor
const int RELAY_GRINDER = 16; // Grinder relay
const int RELAY_CONVEYOR = 15; // Conveyor relay
LiquidCrystal_I2C lcd(0x27, 16, 4);

// Firebase objects
FirebaseData fb_do; 
FirebaseConfig config;
FirebaseAuth auth;

// State variables (track all states separately)
bool firebaseHeaterStatus = false;    // What Firebase says
bool localOvenRunning = false;        // What ESP32 thinks
bool ssrState = false;                // Actual SSR pin state
bool lastFirebaseHeaterStatus = false;
bool grinderState = false;
bool conveyorState = false;
bool conveyorManualOverride = false;   // Manual override state for conveyor

// Timing
unsigned long lastDebugPrint = 0;
unsigned long lastFirebaseRead = 0;
unsigned long lastBtnStart = 0;
unsigned long lastBtnGrinder = 0;
unsigned long lastBtnConveyor = 0;
const unsigned long DEBUG_INTERVAL = 2000; // Print every 2 seconds
const unsigned long FIREBASE_READ_INTERVAL = 1500;
const unsigned long BUTTON_DEBOUNCE = 50; // Debounce time for button

void setup() {
    Serial.begin(115200);
    Serial.println("=== State Synchronization Debug ===");
    
    // Initialize hardware
    pinMode(SSR_OVEN, OUTPUT);
    pinMode(RELAY_GRINDER, OUTPUT);
    pinMode(RELAY_CONVEYOR, OUTPUT);
    digitalWrite(SSR_OVEN, LOW);
    digitalWrite(RELAY_GRINDER, LOW);
    digitalWrite(RELAY_CONVEYOR, LOW);
    
    // Initialize buttons with internal pullup
    pinMode(BTN_START, INPUT_PULLUP);
    pinMode(BTN_GRINDER, INPUT_PULLUP);
    pinMode(BTN_CONVEYOR, INPUT_PULLUP);
    
    Wire.begin(21, 22);
    lcd.init();
    lcd.backlight();
    
    // Connect to WiFi
    WiFi.begin(ssid, password);
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.println("\nWiFi Connected!");
    
    // Initialize Firebase
    config.host = FIREBASE_HOST;
    config.signer.tokens.legacy_token = FIREBASE_AUTH;
    Firebase.begin(&config, &auth);
    Firebase.reconnectWiFi(true);
    
    delay(2000);
    
    Serial.println("Monitoring state synchronization...");
    Serial.println("Format: Firebase | Local | SSR | LCD | Status");
    Serial.println("--------------------------------------------");
    
    lcd.setCursor(0, 0);
    lcd.print("STATE DEBUG");
    lcd.setCursor(0, 1);
    lcd.print("Monitoring...");
}

void loop() {
    // Check physical buttons
    checkPhysicalButtons();
    
    // Read Firebase state
    if (millis() - lastFirebaseRead > FIREBASE_READ_INTERVAL) {
        
        if (Firebase.getBool(fb_do, "/heater/status")) {
            firebaseHeaterStatus = fb_do.boolData();
            
            // Check if Firebase state changed
            if (firebaseHeaterStatus != lastFirebaseHeaterStatus) {
                Serial.print("FIREBASE STATE CHANGED: ");
                Serial.print(lastFirebaseHeaterStatus ? "ON" : "OFF");
                Serial.print(" -> ");
                Serial.println(firebaseHeaterStatus ? "ON" : "OFF");
                
                lastFirebaseHeaterStatus = firebaseHeaterStatus;
                
                // Update local state to match Firebase
                localOvenRunning = firebaseHeaterStatus;
                ssrState = firebaseHeaterStatus;
                digitalWrite(SSR_OVEN, ssrState ? HIGH : LOW);
                
                Serial.print("SYNCED LOCAL STATE TO: ");
                Serial.println(ssrState ? "ON" : "OFF");
            }
            
        } else {
            Serial.println("FIREBASE READ ERROR");
        }
        
        // ===== CONVEYOR SCHEDULER LOGIC =====
        // Only control conveyor automatically if manual override is OFF
        if (!conveyorManualOverride) {
            // Add your conveyor scheduler logic here
            // Example: conveyorState = schedulerShouldBeOn;
            // digitalWrite(RELAY_CONVEYOR, conveyorState ? HIGH : LOW);
            Serial.println("Conveyor controlled by scheduler");
        } else {
            Serial.println("Conveyor controlled by manual override");
        }
        
        lastFirebaseRead = millis();
    }
    
    // Debug print every 2 seconds
    if (millis() - lastDebugPrint > DEBUG_INTERVAL) {
        
        // Read actual SSR pin state
        bool actualSSRState = digitalRead(SSR_OVEN);
        
        Serial.print("Firebase: ");
        Serial.print(firebaseHeaterStatus ? "ON " : "OFF");
        Serial.print(" | Local: ");
        Serial.print(localOvenRunning ? "ON " : "OFF");
        Serial.print(" | SSR: ");
        Serial.print(actualSSRState ? "ON " : "OFF");
        Serial.print(" | LCD: ");
        Serial.print(ssrState ? "ON " : "OFF");
        Serial.print(" | Conv: ");
        Serial.print(conveyorState ? "ON " : "OFF");
        Serial.print(" | ConvMode: ");
        Serial.print(conveyorManualOverride ? "MANUAL" : "AUTO");
        Serial.print(" | ");
        
        // Identify the problem
        if (firebaseHeaterStatus != localOvenRunning) {
            Serial.println("ERROR: Firebase != Local");
        } else if (localOvenRunning != actualSSRState) {
            Serial.println("ERROR: Local != SSR Pin");
        } else if (actualSSRState != ssrState) {
            Serial.println("ERROR: SSR Pin != LCD State");
        } else {
            Serial.println("OK: All states synchronized");
        }
        
        // Update LCD with all states
        lcd.clear();
        lcd.setCursor(0, 0);
        lcd.print("FB:");
        lcd.print(firebaseHeaterStatus ? "ON" : "OFF");
        lcd.print(" LC:");
        lcd.print(localOvenRunning ? "ON" : "OFF");
        
        lcd.setCursor(0, 1);
        lcd.print("SSR:");
        lcd.print(actualSSRState ? "ON" : "OFF");
        lcd.print(" LCD:");
        lcd.print(ssrState ? "ON" : "OFF");
        
        lcd.setCursor(0, 2);
        lcd.print("Conv:");
        lcd.print(conveyorState ? "ON" : "OFF");
        lcd.print(" Mode:");
        lcd.print(conveyorManualOverride ? "MAN" : "AUTO");
        
        lastDebugPrint = millis();
    }
    
    delay(100);
}

void checkPhysicalButtons() {
    // START BUTTON (OVEN CONTROL)
    if (digitalRead(BTN_START) == LOW && millis() - lastBtnStart > BUTTON_DEBOUNCE) {
        localOvenRunning = !localOvenRunning;
        lastBtnStart = millis();
        
        if (!localOvenRunning) {
            ssrState = false;
            digitalWrite(SSR_OVEN, LOW);
            Serial.println(">> OVEN STOPPED (Physical Button)");
        } else {
            Serial.println(">> OVEN STARTED (Physical Button)");
        }
        
        // Update Firebase immediately
        Firebase.setBool(fb_do, "/heater/status", localOvenRunning);
    }

    // GRINDER BUTTON
    if (digitalRead(BTN_GRINDER) == LOW && millis() - lastBtnGrinder > BUTTON_DEBOUNCE) {
        grinderState = !grinderState;
        lastBtnGrinder = millis();
        digitalWrite(RELAY_GRINDER, grinderState ? HIGH : LOW);
        Serial.println(grinderState ? ">> GRINDER ON (Physical)" : ">> GRINDER OFF (Physical)");
        
        // Update Firebase immediately
        Firebase.setBool(fb_do, "/grinder/status", grinderState);
    }

    // CONVEYOR BUTTON (MANUAL OVERRIDE TOGGLE)
    if (digitalRead(BTN_CONVEYOR) == LOW && millis() - lastBtnConveyor > BUTTON_DEBOUNCE) {
        conveyorManualOverride = !conveyorManualOverride;
        lastBtnConveyor = millis();
        
        Serial.print(">> CONVEYOR MANUAL OVERRIDE: ");
        Serial.println(conveyorManualOverride ? "ON" : "OFF");
        
        // When manual override is turned ON, set conveyor to ON as default
        // When manual override is turned OFF, conveyor will be controlled by scheduler
        if (conveyorManualOverride) {
            conveyorState = true;
            digitalWrite(RELAY_CONVEYOR, HIGH);
            Serial.println(">> CONVEYOR ON (Manual Override)");
        }
        
        // Update Firebase immediately
        Firebase.setBool(fb_do, "/conveyor/manual_override", conveyorManualOverride);
        Firebase.setBool(fb_do, "/conveyor/status", conveyorState);
    }
}
