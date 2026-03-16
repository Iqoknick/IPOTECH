/*
 * Firebase Command Debugging Code
 * Use this to verify Firebase commands are being received correctly
 */

#include <WiFi.h>
#include <FirebaseESP32.h>
#include <esp_task_wdt.h>

// Firebase Configuration
const char* ssid = "TP-Link_2C1F";
const char* password = "23046054";
#define FIREBASE_HOST "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
#define FIREBASE_AUTH "GFI2mhoOx6kuKsPvsaJshfNQFRMLwfZRQigGeWiW"

// Test SSR pin
const int SSR_OVEN = 26;

// Firebase objects
FirebaseData fb_do; 
FirebaseConfig config;
FirebaseAuth auth;

// Variables
bool ovenRunning = false;
bool ovenHeaterState = false;
unsigned long lastFirebaseRead = 0;
const unsigned long FIREBASE_READ_INTERVAL = 1000; // Read every second

void setup() {
    Serial.begin(115200);
    Serial.println("=== Firebase Command Debug ===");
    
    // Initialize SSR pin
    pinMode(SSR_OVEN, OUTPUT);
    digitalWrite(SSR_OVEN, LOW);
    
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
    Serial.println("Firebase Ready");
    Serial.println("Monitoring Firebase commands...");
    Serial.println("Format: Timestamp | Firebase Value | Local State | SSR State");
    Serial.println("--------------------------------------------------------");
}

void loop() {
    // Read Firebase commands every second
    if (millis() - lastFirebaseRead > FIREBASE_READ_INTERVAL) {
        
        // Read heater status from Firebase
        if (Firebase.getBool(fb_do, "/heater/status")) {
            bool fbValue = fb_do.boolData();
            
            Serial.print(millis());
            Serial.print(" | Firebase: ");
            Serial.print(fbValue ? "ON" : "OFF");
            Serial.print(" | Local: ");
            Serial.print(ovenRunning ? "ON" : "OFF");
            Serial.print(" | SSR: ");
            Serial.print(ovenHeaterState ? "ON" : "OFF");
            
            // Check if value changed
            if (fbValue != ovenRunning) {
                ovenRunning = fbValue;
                
                if (ovenRunning) {
                    ovenHeaterState = true;
                    digitalWrite(SSR_OVEN, HIGH);
                    Serial.println(" -> STARTED");
                } else {
                    ovenHeaterState = false;
                    digitalWrite(SSR_OVEN, LOW);
                    Serial.println(" -> STOPPED");
                }
            } else {
                Serial.println(" -> NO CHANGE");
            }
            
        } else {
            Serial.print(millis());
            Serial.println(" | Firebase: READ ERROR");
        }
        
        lastFirebaseRead = millis();
    }
    
    delay(100);
}
