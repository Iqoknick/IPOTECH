/*
 * IPOTECH Industrial Monitoring System - ESP32-S3 Version
 * Firebase Real-time Database Integration
 * Hardware: ESP32-S3 with external antenna support
 */

#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <DHT.h>

// Firebase Configuration - Matching Android App
const char* FIREBASE_HOST = "layer-eb465-default-rtdb.europe-west1.firebasedatabase.app";
const char* FIREBASE_API_KEY = "AIzaSyD_ZlLH0tFkZJ0DzXIeGLZP_ZOz3UhLng8";
const char* WIFI_SSID = "TP-Link_2C1F";
const char* WIFI_PASSWORD = "23046054";

// ESP32-S3 Pin Definitions
#define CONVEYOR_RELAY_PIN     1   // Changed from 4
#define PULVERIZER_RELAY_PIN   2   // Same as before
#define HEATER_RELAY_PIN       3   // Changed from 15
#define TEMP_SENSOR_PIN         4   // Changed from 5
#define EMERGENCY_STOP_PIN     5   // Changed from 34

// DHT Sensor Setup
#define DHT_TYPE DHT22
DHT dht(TEMP_SENSOR_PIN, DHT_TYPE);

// Global Variables
bool conveyorStatus = false;
bool pulverizerStatus = false;
bool heaterStatus = false;
float currentTemperature = 0.0;
float currentHumidity = 0.0;
unsigned long lastFirebaseUpdate = 0;
const unsigned long FIREBASE_UPDATE_INTERVAL = 5000; // 5 seconds

void setup() {
    Serial.begin(115200);
    Serial.println("IPOTECH ESP32-S3 Starting...");
    
    // Initialize Pins
    pinMode(CONVEYOR_RELAY_PIN, OUTPUT);
    pinMode(PULVERIZER_RELAY_PIN, OUTPUT);
    pinMode(HEATER_RELAY_PIN, OUTPUT);
    pinMode(EMERGENCY_STOP_PIN, INPUT_PULLUP);
    
    // Set initial states
    digitalWrite(CONVEYOR_RELAY_PIN, LOW);
    digitalWrite(PULVERIZER_RELAY_PIN, LOW);
    digitalWrite(HEATER_RELAY_PIN, LOW);
    
    // Initialize DHT Sensor
    dht.begin();
    
    // Connect to WiFi with ESP32-S3 enhancements
    connectToWiFi();
    
    Serial.println("IPOTECH ESP32-S3 Ready!");
}

void loop() {
    // Read sensors
    readSensors();
    
    // Check emergency stop
    checkEmergencyStop();
    
    // Update Firebase
    if (millis() - lastFirebaseUpdate > FIREBASE_UPDATE_INTERVAL) {
        updateFirebase();
        checkFirebaseCommands();
        lastFirebaseUpdate = millis();
    }
    
    delay(1000);
}

void connectToWiFi() {
    Serial.println("Connecting to WiFi...");
    
    // ESP32-S3 WiFi enhancements
    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false); // Keep connection active
    WiFi.setTxPower(WIFI_POWER_19_5dBm); // Higher power for external antenna
    
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    
    while (WiFi.status() != WL_CONNECTED) {
        delay(1000);
        Serial.print(".");
    }
    
    Serial.println("\nWiFi connected!");
    Serial.print("IP Address: ");
    Serial.println(WiFi.localIP());
    Serial.print("Signal Strength (RSSI): ");
    Serial.print(WiFi.RSSI());
    Serial.println(" dBm");
}

void readSensors() {
    // Read temperature and humidity
    float newTemp = dht.readTemperature();
    float newHum = dht.readHumidity();
    
    // Validate sensor readings
    if (!isnan(newTemp) && !isnan(newHum)) {
        currentTemperature = newTemp;
        currentHumidity = newHum;
        
        Serial.print("Temperature: ");
        Serial.print(currentTemperature);
        Serial.print("°C, Humidity: ");
        Serial.print(currentHumidity);
        Serial.println("%");
    } else {
        Serial.println("Failed to read from DHT sensor!");
    }
}

void checkEmergencyStop() {
    bool emergencyPressed = digitalRead(EMERGENCY_STOP_PIN) == LOW;
    
    if (emergencyPressed) {
        Serial.println("EMERGENCY STOP PRESSED!");
        
        // Turn off all devices
        digitalWrite(CONVEYOR_RELAY_PIN, LOW);
        digitalWrite(PULVERIZER_RELAY_PIN, LOW);
        digitalWrite(HEATER_RELAY_PIN, LOW);
        
        conveyorStatus = false;
        pulverizerStatus = false;
        heaterStatus = false;
        
        // Log emergency stop to Firebase
        sendToFirebase("emergency_stop", "EMERGENCY_STOP_ACTIVATED");
        
        delay(5000); // Debounce
    }
}

void updateFirebase() {
    HTTPClient http;
    String url = "https://" + String(FIREBASE_HOST) + "/sensors.json?key=" + String(FIREBASE_API_KEY);
    
    // Create JSON data matching Android app structure
    DynamicJsonDocument doc(1024);
    doc["temperature"] = currentTemperature;
    doc["humidity"] = currentHumidity;
    doc["timestamp"] = millis();
    doc["device_id"] = "ESP32-S3-001";
    doc["wifi_rssi"] = WiFi.RSSI();
    
    String jsonData;
    serializeJson(doc, jsonData);
    
    // Send to Firebase
    http.begin(url);
    http.addHeader("Content-Type", "application/json");
    int httpResponseCode = http.PUT(jsonData);
    
    if (httpResponseCode > 0) {
        Serial.println("Sensor data sent to Firebase successfully");
        
        // Also update device status paths
        updateDeviceStatus();
    } else {
        Serial.print("Firebase error: ");
        Serial.println(httpResponseCode);
    }
    
    http.end();
}

void updateDeviceStatus() {
    HTTPClient http;
    
    // Update conveyor status
    String conveyorUrl = "https://" + String(FIREBASE_HOST) + "/conveyor/status.json?key=" + String(FIREBASE_API_KEY);
    http.begin(conveyorUrl);
    http.addHeader("Content-Type", "application/json");
    http.PUT(String(conveyorStatus ? "true" : "false"));
    http.end();
    
    // Update pulverizer status  
    String pulverizerUrl = "https://" + String(FIREBASE_HOST) + "/pulverizer/status.json?key=" + String(FIREBASE_API_KEY);
    http.begin(pulverizerUrl);
    http.addHeader("Content-Type", "application/json");
    http.PUT(String(pulverizerStatus ? "true" : "false"));
    http.end();
    
    // Update heater status
    String heaterUrl = "https://" + String(FIREBASE_HOST) + "/heater/status.json?key=" + String(FIREBASE_API_KEY);
    http.begin(heaterUrl);
    http.addHeader("Content-Type", "application/json");
    http.PUT(String(heaterStatus ? "true" : "false"));
    http.end();
    
    Serial.println("Device status updated to Firebase");
}

void checkFirebaseCommands() {
    HTTPClient http;
    
    // Check conveyor command
    String conveyorUrl = "https://" + String(FIREBASE_HOST) + "/conveyor/remote_control.json?key=" + String(FIREBASE_API_KEY);
    http.begin(conveyorUrl);
    int conveyorResponse = http.GET();
    
    if (conveyorResponse > 0) {
        String response = http.getString();
        response.trim(); // Remove whitespace
        
        if (response == "true" || response == "false") {
            bool newConveyorStatus = (response == "true");
            if (newConveyorStatus != conveyorStatus) {
                conveyorStatus = newConveyorStatus;
                digitalWrite(CONVEYOR_RELAY_PIN, conveyorStatus ? HIGH : LOW);
                Serial.println("Conveyor: " + String(conveyorStatus ? "ON" : "OFF") + " (Remote Control)");
                
                // Clear the command after processing
                http.begin(conveyorUrl);
                http.addHeader("Content-Type", "application/json");
                http.DELETE();
                http.end();
            }
        }
    }
    http.end();
    
    // Check pulverizer command
    String pulverizerUrl = "https://" + String(FIREBASE_HOST) + "/pulverizer/remote_control.json?key=" + String(FIREBASE_API_KEY);
    http.begin(pulverizerUrl);
    int pulverizerResponse = http.GET();
    
    if (pulverizerResponse > 0) {
        String response = http.getString();
        response.trim();
        
        if (response == "true" || response == "false") {
            bool newPulverizerStatus = (response == "true");
            if (newPulverizerStatus != pulverizerStatus) {
                pulverizerStatus = newPulverizerStatus;
                digitalWrite(PULVERIZER_RELAY_PIN, pulverizerStatus ? HIGH : LOW);
                Serial.println("Pulverizer: " + String(pulverizerStatus ? "ON" : "OFF") + " (Remote Control)");
                
                // Clear the command after processing
                http.begin(pulverizerUrl);
                http.addHeader("Content-Type", "application/json");
                http.DELETE();
                http.end();
            }
        }
    }
    http.end();
    
    // Check heater command
    String heaterUrl = "https://" + String(FIREBASE_HOST) + "/heater/remote_control.json?key=" + String(FIREBASE_API_KEY);
    http.begin(heaterUrl);
    int heaterResponse = http.GET();
    
    if (heaterResponse > 0) {
        String response = http.getString();
        response.trim();
        
        if (response == "true" || response == "false") {
            bool newHeaterStatus = (response == "true");
            if (newHeaterStatus != heaterStatus) {
                heaterStatus = newHeaterStatus;
                digitalWrite(HEATER_RELAY_PIN, heaterStatus ? HIGH : LOW);
                Serial.println("Heater: " + String(heaterStatus ? "ON" : "OFF") + " (Remote Control)");
                
                // Clear the command after processing
                http.begin(heaterUrl);
                http.addHeader("Content-Type", "application/json");
                http.DELETE();
                http.end();
            }
        }
    }
    http.end();
}

void sendToFirebase(String path, String message) {
    HTTPClient http;
    String url = "https://" + String(FIREBASE_HOST) + "/logs.json?key=" + String(FIREBASE_API_KEY);
    
    DynamicJsonDocument doc(512);
    doc["timestamp"] = millis();
    doc["message"] = message;
    doc["device"] = "ESP32-S3";
    doc["path"] = path;
    
    String jsonData;
    serializeJson(doc, jsonData);
    
    http.begin(url);
    http.addHeader("Content-Type", "application/json");
    http.POST(jsonData);
    http.end();
    
    Serial.println("Log sent to Firebase: " + message);
}
