#include <WiFi.h>
#include <FirebaseESP32.h>
#include <Adafruit_MAX31855.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>

// ===== PIN DEFINITIONS =====
int thermoSO  = 19;
int thermoCS  = 5;
int thermoSCK = 18;

int SSR_OVEN        = 17; // SSR for oven heater
int RELAY_GRINDER   = 16; // Relay for grinder
int RELAY_CONVEYOR  = 15; // Relay for conveyor

int BTN_START     = 13; // Start/Stop oven process (HEATER)
int BTN_GRINDER   = 4; // Toggle grinder
int BTN_CONVEYOR  = 14; // Toggle conveyor

// ===== WIFI & FIREBASE CONFIGURATION =====
const char* ssid = "YOUR_WIFI_SSID";
const char* password = "YOUR_WIFI_PASSWORD";
#define FIREBASE_HOST "YOUR_FIREBASE_DATABASE_URL"
#define FIREBASE_AUTH "YOUR_FIREBASE_AUTH_TOKEN"

// ===== OBJECTS =====
Adafruit_MAX31855 thermocouple(thermoSCK, thermoCS, thermoSO);
LiquidCrystal_I2C lcd(0x27, 16, 4); // I2C address 0x27, 16 columns, 4 rows
FirebaseData fb_do;
FirebaseConfig config;
FirebaseAuth auth;

// ===== VARIABLES =====
bool ovenRunning      = false;
bool ovenHeaterState  = false;
bool grinderState     = false;
bool conveyorState    = false;
bool conveyorManualOverride = false;   // Manual override state for conveyor

float lowTemp  = 50.0;
float highTemp = 200.0;

unsigned long lastBtnStart    = 0;
unsigned long lastBtnGrinder  = 0;
unsigned long lastBtnConveyor = 0;
unsigned long lastFirebaseUpdate = 0;
unsigned long lastFirebaseRead = 0;
unsigned long lastLCDUpdate = 0;

const unsigned long debounceDelay = 400;
const unsigned long FIREBASE_UPDATE_INTERVAL = 1000; // Update Firebase every 1 second
const unsigned long FIREBASE_READ_INTERVAL = 1500;  // Read from Firebase every 1.5 seconds
const unsigned long LCD_UPDATE_INTERVAL = 500;      // Update LCD every 0.5 seconds

// Firebase state tracking
bool firebaseOvenRunning = false;
bool firebaseGrinderState = false;
bool firebaseConveyorState = false;

void setup() {
  Serial.begin(115200);
  Serial.println("=== SMART OVEN + GRINDER + CONVEYOR CONTROLLER WITH FIREBASE ===");

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

  // Initialize LCD
  Wire.begin(21, 22); // SDA = GPIO21, SCL = GPIO22 (default ESP32 I2C pins)
  lcd.init();
  lcd.backlight();
  lcd.clear();
  
  // Display startup message on LCD
  lcd.setCursor(0, 0);
  lcd.print("SMART OVEN SYS");
  lcd.setCursor(0, 1);
  lcd.print("Initializing...");
  lcd.setCursor(0, 2);
  lcd.print("Connecting WiFi");

  // Connect to WiFi
  Serial.print("Connecting to WiFi...");
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

  // Update LCD for Firebase connection
  lcd.setCursor(0, 2);
  lcd.print("Connecting FB  ");
  
  delay(2000);
  
  // Initialize Firebase with current states
  updateFirebaseStates();
  
  // Show ready message on LCD
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("SYSTEM READY");
  lcd.setCursor(0, 1);
  lcd.print("Press START");
  lcd.setCursor(0, 2);
  lcd.print("Temp: --.- C");
  lcd.setCursor(0, 3);
  lcd.print("O:OFF G:OFF C:OFF");
  
  Serial.println("System Ready. Press START to run oven.");
  Serial.println("Firebase synchronization active.");
}

void loop() {
  float tempC = thermocouple.readCelsius();

  // Read states from Firebase
  readFirebaseStates();

  // ===== LOCAL BUTTON CONTROLS =====
  
  // START BUTTON (HEATER MANUAL CONTROL)
  if (digitalRead(BTN_START) == LOW && millis() - lastBtnStart > debounceDelay) {
    ovenHeaterState = !ovenHeaterState;
    lastBtnStart = millis();
    digitalWrite(SSR_OVEN, ovenHeaterState ? HIGH : LOW);

    if (ovenHeaterState) {
      Serial.println(">> HEATER ON (Manual Control)");
    } else {
      Serial.println(">> HEATER OFF (Manual Control)");
    }
    
    // Update Firebase immediately when button pressed
    updateFirebaseStates();
  }

  // GRINDER BUTTON
  if (digitalRead(BTN_GRINDER) == LOW && millis() - lastBtnGrinder > debounceDelay) {
    grinderState = !grinderState;
    lastBtnGrinder = millis();
    digitalWrite(RELAY_GRINDER, grinderState ? HIGH : LOW);
    Serial.println(grinderState ? ">> GRINDER ON (Local)" : ">> GRINDER OFF (Local)");
    
    // Update Firebase immediately when button pressed
    updateFirebaseStates();
  }

  // CONVEYOR BUTTON (MANUAL OVERRIDE TOGGLE)
  if (digitalRead(BTN_CONVEYOR) == LOW && millis() - lastBtnConveyor > debounceDelay) {
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
    
    // Update Firebase immediately when button pressed
    updateFirebaseStates();
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

  // ===== PERIODIC FIREBASE UPDATES =====
  if (millis() - lastFirebaseUpdate > FIREBASE_UPDATE_INTERVAL) {
    updateFirebaseStates();
    lastFirebaseUpdate = millis();
  }

  // ===== UPDATE LCD DISPLAY =====
  if (millis() - lastLCDUpdate > LCD_UPDATE_INTERVAL) {
    updateLCDDisplay(tempC);
    lastLCDUpdate = millis();
  }

  // ===== SERIAL DISPLAY =====
  Serial.print("Temp: ");
  Serial.print(tempC);
  Serial.print(" C | Oven:");
  Serial.print(ovenHeaterState ? "ON" : "OFF");
  Serial.print(" | Grinder:");
  Serial.print(grinderState ? "ON" : "OFF");
  Serial.print(" | Conveyor:");
  Serial.print(conveyorState ? "ON" : "OFF");
  Serial.print(" | FB_Oven:");
  Serial.print(firebaseOvenRunning ? "ON" : "OFF");
  Serial.print(" | FB_Grinder:");
  Serial.print(firebaseGrinderState ? "ON" : "OFF");
  Serial.print(" | FB_Conveyor:");
  Serial.println(firebaseConveyorState ? "ON" : "OFF");

  delay(500);
}

void readFirebaseStates() {
  if (millis() - lastFirebaseRead > FIREBASE_READ_INTERVAL) {
    
    // Read grinder state from Firebase
    if (Firebase.getBool(fb_do, "/grinder/state")) {
      bool newFirebaseGrinderState = fb_do.boolData();
      if (newFirebaseGrinderState != firebaseGrinderState) {
        firebaseGrinderState = newFirebaseGrinderState;
        
        // Sync local state with Firebase if different
        if (firebaseGrinderState != grinderState) {
          grinderState = firebaseGrinderState;
          digitalWrite(RELAY_GRINDER, grinderState ? HIGH : LOW);
          Serial.println(">> GRINDER STATE SYNCED FROM FIREBASE");
        }
      }
    }

    // Read conveyor state from Firebase
    if (Firebase.getBool(fb_do, "/conveyor/state")) {
      bool newFirebaseConveyorState = fb_do.boolData();
      if (newFirebaseConveyorState != firebaseConveyorState) {
        firebaseConveyorState = newFirebaseConveyorState;
        
        // Sync local state with Firebase if different
        if (firebaseConveyorState != conveyorState) {
          conveyorState = firebaseConveyorState;
          digitalWrite(RELAY_CONVEYOR, conveyorState ? HIGH : LOW);
          Serial.println(">> CONVEYOR STATE SYNCED FROM FIREBASE");
        }
      }
    }

    lastFirebaseRead = millis();
  }
}

void updateFirebaseStates() {
  // Update oven heater state (temperature controlled only)
  Firebase.setBool(fb_do, "/oven/heater", ovenHeaterState);
  
  // Update temperature
  float tempC = thermocouple.readCelsius();
  Firebase.setFloat(fb_do, "/oven/temperature", tempC);
  Firebase.setFloat(fb_do, "/oven/lowTemp", lowTemp);
  Firebase.setFloat(fb_do, "/oven/highTemp", highTemp);
  
  // Update grinder state
  Firebase.setBool(fb_do, "/grinder/state", grinderState);
  
  // Update conveyor state and manual override
  Firebase.setBool(fb_do, "/conveyor/state", conveyorState);
  Firebase.setBool(fb_do, "/conveyor/manual_override", conveyorManualOverride);
  
  // Update system status
  Firebase.setString(fb_do, "/system/status", ovenHeaterState ? "heater_manual" : "heater_off");
  Firebase.setInt(fb_do, "/system/uptime", millis() / 1000);
  
  // Update WiFi signal strength
  Firebase.setInt(fb_do, "/system/wifi_rssi", WiFi.RSSI());
  
  // Update local tracking variables
  firebaseGrinderState = grinderState;
  firebaseConveyorState = conveyorState;
}

void updateLCDDisplay(float tempC) {
  lcd.clear();
  
  // Line 1: Temperature and Heater Status
  lcd.setCursor(0, 0);
  lcd.print("Temp:");
  lcd.print(tempC, 1);
  lcd.print("C ");
  
  if (ovenHeaterState) {
    lcd.print("HEATER ON");
  } else {
    lcd.print("HEATER OFF");
  }
  
  // Line 2: Countdown Status
  lcd.setCursor(0, 1);
  lcd.print("Countdown:");
  if (tempC <= (lowTemp - 2.0)) {
    lcd.print("READY");
  } else {
    lcd.print("WAIT");
  }
  
  // Line 3: Grinder and Conveyor Status
  lcd.setCursor(0, 2);
  lcd.print("Grinder:");
  lcd.print(grinderState ? "ON " : "OFF");
  lcd.print(" Conv:");
  lcd.print(conveyorState ? "ON " : "OFF");
  
  // Line 4: WiFi, Firebase, and Conveyor Mode Status
  lcd.setCursor(0, 3);
  lcd.print("WF:");
  if (WiFi.status() == WL_CONNECTED) {
    lcd.print("OK");
  } else {
    lcd.print("ERR");
  }
  
  lcd.print(" FB:");
  if (Firebase.ready()) {
    lcd.print("OK");
  } else {
    lcd.print("ERR");
  }
  
  lcd.print(" C:");
  lcd.print(conveyorManualOverride ? "MAN" : "AUTO");
}
