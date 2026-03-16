#include <WiFi.h>
#include <FirebaseESP32.h>
#include <Adafruit_MAX31855.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>

// ===== WIFI =====
#define WIFI_SSID "TP-Link_2C1F"
#define WIFI_PASSWORD "23046054"

// ===== FIREBASE =====
#define API_KEY "GFI2mhoOx6kuKsPvsaJshfNQFRMLwfZRQigGeWiW"
#define DATABASE_URL "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"

// ===== FIREBASE OBJECTS =====
FirebaseData fb_do;
FirebaseAuth auth;
FirebaseConfig config;

// ===== LCD =====
LiquidCrystal_I2C lcd(0x27,16,2);

// ===== PIN DEFINITIONS =====
int thermoSO  = 19;
int thermoCS  = 5;
int thermoSCK = 18;

int SSR_OVEN        = 17; 
int RELAY_PULVERIZER = 16; // Renamed from Grinder
int RELAY_CONVEYOR  = 15;

int BTN_HEATER      = 13; // Physical button for Heater
int BTN_PULVERIZER  = 4;  // Pin 4
int BTN_CONVEYOR    = 14; // Pin 14

// ===== SENSOR OBJECT =====
Adafruit_MAX31855 thermocouple(thermoSCK, thermoCS, thermoSO);

// ===== VARIABLES =====
bool ovenHeaterState   = false;
bool pulverizerState   = false;
bool conveyorState     = false;

bool conveyorManualOverride = false; // Only conveyor has manual override

float tempLow  = 120.0;
float tempHigh = 130.0;

unsigned long lastBtnHeater    = 0;
unsigned long lastBtnPulverizer = 0;
unsigned long lastBtnConveyor  = 0;
unsigned long lastFirebaseRead = 0;

// Stop at timers for app scheduler
unsigned long heaterStopAt = 0;
unsigned long conveyorStopAt = 0;

const unsigned long debounceDelay = 400;
const unsigned long firebaseInterval = 1000; 

// ===== FIREBASE PATHS (Synced with Android App) =====
String pathHeaterStatus     = "/heater/status";         // App Command
String pathHeaterRelay      = "/heater/relay_status";   // Hardware State
String pathHeaterStopAt    = "/heater/stop_at";        // App timer

String pathPulverizerStatus = "/pulverizer/status";     // App uses 'pulverizer'
String pathPulverizerRelay  = "/pulverizer/relay_status";

String pathConveyorStatus   = "/conveyor/status";
String pathConveyorRelay    = "/conveyor/relay_status";
String pathConveyorManual   = "/conveyor/manual_override";
String pathConveyorStopAt   = "/conveyor/stop_at";      // App scheduler

String pathTemp             = "/temperature/current";

void setup() {
  Serial.begin(115200);

  lcd.init();
  lcd.backlight();
  lcd.setCursor(0,0);
  lcd.print("IPOTECH SYSTEM");

  pinMode(SSR_OVEN, OUTPUT);
  pinMode(RELAY_PULVERIZER, OUTPUT);
  pinMode(RELAY_CONVEYOR, OUTPUT);

  digitalWrite(SSR_OVEN, LOW);
  digitalWrite(RELAY_PULVERIZER, LOW);
  digitalWrite(RELAY_CONVEYOR, LOW);

  pinMode(BTN_HEATER, INPUT_PULLUP);
  pinMode(BTN_PULVERIZER, INPUT_PULLUP);
  pinMode(BTN_CONVEYOR, INPUT_PULLUP);

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) { 
    delay(500); 
    Serial.print("."); 
  }
  
  config.api_key = API_KEY;
  config.database_url = DATABASE_URL;
  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);
  Serial.println("\nFirebase Connected");
}

void loop() {
  float tempC = thermocouple.readCelsius();

  // ===== Firebase Sync (Every 1 Second) =====
  if(millis() - lastFirebaseRead > firebaseInterval){
    lastFirebaseRead = millis();

    // 1. Read Overrides and Stop Times
    if(Firebase.getBool(fb_do, pathConveyorManual)) conveyorManualOverride = fb_do.boolData();
    if(Firebase.getInt(fb_do, pathHeaterStopAt)) heaterStopAt = fb_do.intData();
    if(Firebase.getInt(fb_do, pathConveyorStopAt)) conveyorStopAt = fb_do.intData();

    // 2. Heater Control Logic (Manual Only - No Hysteresis)
    // Check if heater should stop based on stop_at timer
    if(heaterStopAt > 0 && millis() >= heaterStopAt && ovenHeaterState){
      ovenHeaterState = false;
      digitalWrite(SSR_OVEN, LOW);
      Firebase.setBool(fb_do, pathHeaterRelay, false);
      Firebase.setBool(fb_do, pathHeaterStopAt, 0); // Reset timer
    }
    
    // App control for heater
    if(Firebase.getBool(fb_do, pathHeaterStatus)){
      bool targetState = fb_do.boolData();
      if(targetState != ovenHeaterState){
        ovenHeaterState = targetState;
        digitalWrite(SSR_OVEN, ovenHeaterState ? HIGH : LOW);
        Firebase.setBool(fb_do, pathHeaterRelay, ovenHeaterState);
      }
    }

    // 3. Pulverizer Control (Always available to App - NO override)
    if(Firebase.getBool(fb_do, pathPulverizerStatus)){
      bool targetState = fb_do.boolData();
      if(targetState != pulverizerState){
        pulverizerState = targetState;
        digitalWrite(RELAY_PULVERIZER, pulverizerState ? HIGH : LOW);
        Firebase.setBool(fb_do, pathPulverizerRelay, pulverizerState);
      }
    }

    // 4. Conveyor Control (Only available to App if Override is ON)
    // Check if conveyor should stop based on stop_at timer
    if(conveyorStopAt > 0 && millis() >= conveyorStopAt && conveyorState && !conveyorManualOverride){
      conveyorState = false;
      digitalWrite(RELAY_CONVEYOR, LOW);
      Firebase.setBool(fb_do, pathConveyorRelay, false);
      Firebase.setBool(fb_do, pathConveyorStopAt, 0); // Reset timer
    }
    
    if(conveyorManualOverride) {
      if(Firebase.getBool(fb_do, pathConveyorStatus)){
        bool targetState = fb_do.boolData();
        if(targetState != conveyorState){
          conveyorState = targetState;
          digitalWrite(RELAY_CONVEYOR, conveyorState ? HIGH : LOW);
          Firebase.setBool(fb_do, pathConveyorRelay, conveyorState);
        }
      }
    }

    Firebase.setFloat(fb_do, pathTemp, tempC);
    Firebase.setInt(fb_do, pathHeaterStopAt, heaterStopAt);
    Firebase.setInt(fb_do, pathConveyorStopAt, conveyorStopAt);
  }

  // ===== Physical Buttons Logic =====

  // HEATER BUTTON (GPIO 13): Direct manual control
  if(digitalRead(BTN_HEATER) == LOW && millis() - lastBtnHeater > debounceDelay){
    ovenHeaterState = !ovenHeaterState;
    lastBtnHeater = millis();
    digitalWrite(SSR_OVEN, ovenHeaterState ? HIGH : LOW);
    
    // Reset stop_at timer when manually controlled
    heaterStopAt = 0;
    
    // Update Firebase to reflect physical button press
    Firebase.setBool(fb_do, pathHeaterStatus, ovenHeaterState);
    Firebase.setBool(fb_do, pathHeaterRelay, ovenHeaterState);
    Firebase.setInt(fb_do, pathHeaterStopAt, 0);
  }

  // PULVERIZER BUTTON: Always works (NO override restriction)
  if(digitalRead(BTN_PULVERIZER) == LOW && millis() - lastBtnPulverizer > debounceDelay){
    pulverizerState = !pulverizerState;
    lastBtnPulverizer = millis();
    digitalWrite(RELAY_PULVERIZER, pulverizerState ? HIGH : LOW);
    
    // Update Firebase to reflect physical button press
    Firebase.setBool(fb_do, pathPulverizerStatus, pulverizerState);
    Firebase.setBool(fb_do, pathPulverizerRelay, pulverizerState);
  }

  // CONVEYOR BUTTON: Toggles manual override
  if(digitalRead(BTN_CONVEYOR) == LOW && millis() - lastBtnConveyor > debounceDelay){
    conveyorManualOverride = !conveyorManualOverride; // Toggle override
    lastBtnConveyor = millis();
    
    // When manual override is turned ON, set conveyor to ON as default
    if(conveyorManualOverride) {
      conveyorState = true;
      digitalWrite(RELAY_CONVEYOR, HIGH);
      conveyorStopAt = 0; // Reset timer when manual override ON
    }
    
    // Update Firebase override state
    Firebase.setBool(fb_do, pathConveyorManual, conveyorManualOverride);
    Firebase.setBool(fb_do, pathConveyorStatus, conveyorState);
    Firebase.setBool(fb_do, pathConveyorRelay, conveyorState);
    Firebase.setInt(fb_do, pathConveyorStopAt, conveyorStopAt);
  }

  // ===== LCD Display =====
  lcd.setCursor(0,0);
  lcd.print("Temp: "); lcd.print(tempC, 1); lcd.print("C  ");
  lcd.setCursor(0,1);
  lcd.print(ovenHeaterState ? "H:ON " : "H:OFF");
  lcd.print(pulverizerState ? " P:ON" : " P:OFF");

  delay(50); 
}
