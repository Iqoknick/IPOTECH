# MAX6675 Thermocouple Troubleshooting Guide

## QUICK DIAGNOSTIC
1. Upload `thermocouple_test.ino` first
2. Open Serial Monitor (115200 baud)
3. Check the output for error patterns

## HARDWARE FIXES (Most Common Issues)

### 1. Wiring Problems (90% of issues)
```
MAX6675 Pinout:
VCC  → 3.3V or 5V (check module)
GND  → GND
SO   → GPIO 19 (your code)
CS   → GPIO 5  (your code) 
SCK  → GPIO 18 (your code)
```

**Common wiring mistakes:**
- SO and SCK swapped
- CS pin wrong
- Loose connections on breadboard
- Thermocouple wires reversed

### 2. Power Supply Issues
- **Use 5V** if MAX6675 has 5V regulator
- **Use 3.3V** if module is 3.3V only
- Add **100µF capacitor** between VCC and GND
- Check power supply stability with multimeter

### 3. Thermocouple Connection
- **K-type thermocouple only** (yellow/red wires)
- **Yellow wire** → MAX6675 T+ terminal
- **Red wire** → MAX6675 T- terminal
- Ensure **tight screw terminals**
- Check for **corrosion** on terminals

### 4. Noise Reduction
- Keep thermocouple wires **away from relay power lines**
- Use **twisted pair** wiring
- Add **0.1µF capacitor** between SO and GND
- Use **shielded cable** for long distances

### 5. Grounding Issues
- Connect MAX6675 GND to ESP32 GND
- Use **star grounding** if multiple devices
- Avoid ground loops

## SOFTWARE FIXES

### 1. Timing Issues
MAX6675 needs **250ms conversion time**:
```cpp
// Add this delay before reading
digitalWrite(thermoCS, LOW);
delay(1); // Small delay for chip select
float temp = thermocouple.readCelsius();
digitalWrite(thermoCS, HIGH);
delay(250); // Wait for next conversion
```

### 2. Multiple Reading Average
```cpp
float getStableTemp() {
  float readings[5];
  for(int i = 0; i < 5; i++) {
    readings[i] = thermocouple.readCelsius();
    delay(100); // Between readings
  }
  
  // Remove outliers and average
  float sum = 0, valid = 0;
  for(int i = 0; i < 5; i++) {
    if(readings[i] > 0 && readings[i] < 500) {
      sum += readings[i];
      valid++;
    }
  }
  
  return valid > 0 ? sum / valid : NAN;
}
```

### 3. Error Handling
```cpp
float readTemperature() {
  float temp = thermocouple.readCelsius();
  
  // Check for errors
  if(isnan(temp) || temp <= 0.0) {
    Serial.println("Thermocouple error detected");
    return NAN;
  }
  
  // Sanity check
  if(temp < -50 || temp > 500) {
    Serial.println("Temperature out of range: " + String(temp));
    return NAN;
  }
  
  return temp;
}
```

## TESTING PROCEDURE

### Step 1: Basic Connection Test
1. Upload test code
2. Check Serial Monitor
3. Look for "OK" status messages

### Step 2: Room Temperature Test
- Thermocouple at room temp should read ~20-25°C
- If reading 0°C or error → wiring issue

### Step 3: Heat Test
- Carefully heat thermocouple tip
- Temperature should rise smoothly
- No sudden jumps to 0°C

### Step 4: Noise Test
- Run relays while monitoring
- Temperature should remain stable
- If fluctuating → noise issue

## COMMON ERROR MESSAGES

### "NO CONNECTION"
- Check all wiring
- Verify pin assignments
- Test with multimeter

### "OPEN THERMOCOUPLE"
- Thermocouple disconnected
- Bad thermocouple
- Loose terminal screws

### "INVALID READING"
- Power supply issue
- Noise interference
- Faulty MAX6675 chip

## REPLACEMENT PARTS NEEDED
- MAX6675 module (~$2-5)
- K-type thermocouple (~$1-3)
- 0.1µF ceramic capacitor
- 100µF electrolytic capacitor
- Jumper wires
- Screw terminal (if needed)

## FINAL VERIFICATION
After fixes, thermocouple should:
- Read room temperature accurately
- Respond smoothly to heat changes
- Show no error messages
- Remain stable during relay operation
