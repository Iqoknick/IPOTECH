/*
 * MAX6675 Thermocouple Diagnostic & Testing Code
 * Use this to isolate and fix thermocouple reading issues
 */

#include <max6675.h>

// Pin Definitions - MUST match your main code
const int thermoSO  = 19;
const int thermoCS  = 5;
const int thermoSCK = 18;

// Create thermocouple object
MAX6675 thermocouple(thermoSCK, thermoCS, thermoSO);

void setup() {
    Serial.begin(115200);
    Serial.println("=== MAX6675 Thermocouple Diagnostic ===");
    
    // Initialize pins
    pinMode(thermoCS, OUTPUT);
    digitalWrite(thermoCS, HIGH); // Deselect chip initially
    
    delay(1000); // Let thermocouple stabilize
    
    Serial.println("Starting continuous temperature readings...");
    Serial.println("Format: Raw Reading | Temperature (°C) | Status");
    Serial.println("---------------------------------------------");
}

void loop() {
    // Read raw data
    uint16_t rawValue = thermocouple.readRaw();
    float tempC = thermocouple.readCelsius();
    
    // Diagnostic output
    Serial.print("Raw: ");
    Serial.print(rawValue);
    Serial.print(" | Temp: ");
    
    if (isnan(tempC) || tempC <= 0.0) {
        Serial.print("ERROR");
        Serial.print(" | Status: ");
        
        // Analyze the error
        if (rawValue == 0 || rawValue == 0xFFFF) {
            Serial.println("NO CONNECTION - Check wiring");
        } else if (rawValue & 0x04) {
            Serial.println("OPEN THERMOCOUPLE - Check thermocouple connection");
        } else if (tempC <= 0.0) {
            Serial.println("INVALID READING - Check power supply");
        } else {
            Serial.println("UNKNOWN ERROR");
        }
    } else {
        Serial.print(tempC, 2);
        Serial.print("°C | Status: OK");
        
        // Temperature sanity check
        if (tempC < -50) {
            Serial.print(" (TOO COLD - Check connection)");
        } else if (tempC > 500) {
            Serial.print(" (TOO HOT - Check for noise)");
        } else {
            Serial.print(" (VALID)");
        }
        Serial.println();
    }
    
    delay(2000); // 2 second interval for stable readings
}
