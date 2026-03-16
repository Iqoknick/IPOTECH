#include <max6675.h>
#include <WiFi.h>
#include <ArduinoJson.h>
#include <Firebase_ESP_Client.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <esp_task_wdt.h>
#include <Preferences.h>

// ===== CONFIGURATION =====
#define SSR_ON  HIGH
#define SSR_OFF LOW

// ===== PINS (ESP32 DEV MODULE) =====
const int thermoSO  = 19;
const int thermoCS  = 5;
const int thermoSCK = 18;

const int SSR_OVEN      = 17;
const int SSR_GRINDER   = 16;
const int SSR_CONVEYOR  = 15;

const int BTN_START     = 13;
const int BTN_GRINDER   = 4;
const int BTN_CONVEYOR  = 14;

// ===== LCD =====
LiquidCrystal_I2C lcd(0x27, 20, 4);

// ===== MAX6675 =====
MAX6675 thermocouple(thermoSCK, thermoCS, thermoSO);

// ===== WIFI =====
const char* ssid = "TP-Link_2C1F";
const char* password = "23046054";

// ===== FIREBASE =====
#define FIREBASE_HOST "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
#define FIREBASE_API_KEY "AIzaSyD_ZlLH0tFkZJ0DzXIeGLZP_ZOz3UhLng8"

FirebaseData fb_do;
FirebaseAuth auth;
FirebaseConfig config;

// ===== STATES =====
bool ovenMasterStatus = false;
bool relayState = false;
bool grinderState = false;
bool conveyorState = false;

float currentTemp = 0;

// ===== TEMP CONTROL =====
float tempLow = 120.0;
float tempHigh = 130.0;

// ===== SENSOR ERROR =====
int sensorErrorCount = 0;
const int MAX_SENSOR_ERRORS = 5;

// ===== TIMERS =====
unsigned long lastFirebaseSync = 0;
const unsigned long debounceDelay = 500;

// ===== STORAGE =====
Preferences preferences;

void globalShutdown() {

  ovenMasterStatus = false;
  relayState = false;
  grinderState = false;
  conveyorState = false;

  digitalWrite(SSR_OVEN, SSR_OFF);
  digitalWrite(SSR_GRINDER, SSR_OFF);
  digitalWrite(SSR_CONVEYOR, SSR_OFF);

  lcd.clear();
  lcd.setCursor(0,0);
  lcd.print(" SYSTEM HALTED ");
  lcd.setCursor(0,1);
  lcd.print(" SAFETY STOP ");

  while(1){
    esp_task_wdt_reset();
    delay(1000);
  }
}

void setup() {

  Serial.begin(115200);

  // ===== OUTPUTS =====
  pinMode(SSR_OVEN, OUTPUT);
  pinMode(SSR_GRINDER, OUTPUT);
  pinMode(SSR_CONVEYOR, OUTPUT);

  digitalWrite(SSR_OVEN, SSR_OFF);
  digitalWrite(SSR_GRINDER, SSR_OFF);
  digitalWrite(SSR_CONVEYOR, SSR_OFF);

  // ===== BUTTONS =====
  pinMode(BTN_START, INPUT_PULLUP);
  pinMode(BTN_GRINDER, INPUT_PULLUP);
  pinMode(BTN_CONVEYOR, INPUT_PULLUP);

  // ===== EEPROM =====
  preferences.begin("ipotech", false);
  ovenMasterStatus = preferences.getBool("ovenState", false);

  // ===== WATCHDOG =====
  esp_task_wdt_config_t wdt_config = {
    .timeout_ms = 20000,
    .idle_core_mask = 0,
    .trigger_panic = true
  };

  esp_task_wdt_init(&wdt_config);
  esp_task_wdt_add(NULL);

  // ===== I2C FOR LCD (ESP32 DEV) =====
  Wire.begin(21, 22);  // SDA=21, SCL=22 (standard ESP32 I2C pins)

  lcd.init();
  lcd.backlight();

  lcd.setCursor(0,0);
  lcd.print("IPOTECH SYSTEM");

  // ===== WIFI =====
  WiFi.begin(ssid, password);

  while(WiFi.status() != WL_CONNECTED){
    delay(500);
    Serial.print(".");
  }

  Serial.println("WiFi Connected");

  // ===== FIREBASE =====
  config.host = FIREBASE_HOST;
  config.api_key = FIREBASE_API_KEY;

  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);

  lcd.clear();
  lcd.print("SYSTEM READY");
}

void loop() {

  esp_task_wdt_reset();

  // ===== TEMPERATURE READ =====
  static unsigned long lastTempRead = 0;

  if(millis() - lastTempRead > 1000){

    float val = thermocouple.readCelsius();

    if(isnan(val) || val <= 0 || val > 300){
      sensorErrorCount++;
      currentTemp = 0;
    }
    else{
      sensorErrorCount = 0;
      currentTemp = val;
    }

    lastTempRead = millis();
  }

  // ===== OVEN CONTROL =====
  if(ovenMasterStatus){

    if(currentTemp >= tempHigh){
      relayState = false;
    }

    else if(currentTemp <= tempLow){
      relayState = true;
    }

  }
  else{
    relayState = false;
  }

  digitalWrite(SSR_OVEN, relayState ? SSR_ON : SSR_OFF);

  // ===== BUTTONS =====

  static unsigned long lastBtnStart = 0;
  static unsigned long lastBtnGrinder = 0;
  static unsigned long lastBtnConveyor = 0;

  if(digitalRead(BTN_START) == LOW && millis()-lastBtnStart > debounceDelay){

    ovenMasterStatus = !ovenMasterStatus;

    preferences.putBool("ovenState", ovenMasterStatus);

    if(Firebase.ready()){
      Firebase.RTDB.setBool(&fb_do, "/heater/status", ovenMasterStatus);
    }

    lastBtnStart = millis();
  }

  if(digitalRead(BTN_GRINDER) == LOW && millis()-lastBtnGrinder > debounceDelay){

    grinderState = !grinderState;

    digitalWrite(SSR_GRINDER, grinderState ? SSR_ON : SSR_OFF);

    if(Firebase.ready()){
      Firebase.RTDB.setBool(&fb_do, "/pulverizer/status", grinderState);
    }

    lastBtnGrinder = millis();
  }

  if(digitalRead(BTN_CONVEYOR) == LOW && millis()-lastBtnConveyor > debounceDelay){

    conveyorState = !conveyorState;

    digitalWrite(SSR_CONVEYOR, conveyorState ? SSR_ON : SSR_OFF);

    if(Firebase.ready()){
      Firebase.RTDB.setBool(&fb_do, "/conveyor/status", conveyorState);
    }

    lastBtnConveyor = millis();
  }

  // ===== FIREBASE COMMAND LISTENER =====
  handleFirebaseCommands();

  // ===== LCD =====
  static unsigned long lastLCDUpdate = 0;

  if(millis() - lastLCDUpdate > 2000){

    lcd.clear();

    lcd.setCursor(0,0);
    lcd.print("TEMP: ");
    lcd.print(currentTemp);

    lcd.setCursor(0,1);
    lcd.print("OVEN:");
    lcd.print(ovenMasterStatus ? "RUN":"OFF");

    lcd.setCursor(0,2);
    lcd.print("CNV:");
    lcd.print(conveyorState ? "ON":"OFF");

    lcd.print(" PLV:");
    lcd.print(grinderState ? "ON":"OFF");

    lastLCDUpdate = millis();
  }
}

void handleFirebaseCommands() {
  if(Firebase.ready()) {
    
    // Listen for heater remote control
    if(Firebase.RTDB.getBool(&fb_do, "/heater/remote_control")) {
      if(fb_do.dataTypeEnum() == firebase_rtdb_data_type_boolean) {
        bool newHeaterStatus = fb_do.to<bool>();
        if(newHeaterStatus != ovenMasterStatus) {
          ovenMasterStatus = newHeaterStatus;
          preferences.putBool("ovenState", ovenMasterStatus);
          Serial.println("Heater: " + String(ovenMasterStatus ? "ON" : "OFF") + " (Remote Control)");
          
          // Clear the command
          Firebase.RTDB.deleteNode(&fb_do, "/heater/remote_control");
        }
      }
    }
    
    // Listen for grinder remote control
    if(Firebase.RTDB.getBool(&fb_do, "/pulverizer/remote_control")) {
      if(fb_do.dataTypeEnum() == firebase_rtdb_data_type_boolean) {
        bool newGrinderStatus = fb_do.to<bool>();
        if(newGrinderStatus != grinderState) {
          grinderState = newGrinderStatus;
          digitalWrite(SSR_GRINDER, grinderState ? SSR_ON : SSR_OFF);
          Serial.println("Grinder: " + String(grinderState ? "ON" : "OFF") + " (Remote Control)");
          
          // Clear the command
          Firebase.RTDB.deleteNode(&fb_do, "/pulverizer/remote_control");
        }
      }
    }
    
    // Listen for conveyor remote control
    if(Firebase.RTDB.getBool(&fb_do, "/conveyor/remote_control")) {
      if(fb_do.dataTypeEnum() == firebase_rtdb_data_type_boolean) {
        bool newConveyorStatus = fb_do.to<bool>();
        if(newConveyorStatus != conveyorState) {
          conveyorState = newConveyorStatus;
          digitalWrite(SSR_CONVEYOR, conveyorState ? SSR_ON : SSR_OFF);
          Serial.println("Conveyor: " + String(conveyorState ? "ON" : "OFF") + " (Remote Control)");
          
          // Clear the command
          Firebase.RTDB.deleteNode(&fb_do, "/conveyor/remote_control");
        }
      }
    }
  }
}
