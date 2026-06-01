# IPOTECH  
### Integrated Poultry Optimization Technology for Organic Fertilizer Production  

**IPOTECH** is an IoT-driven industrial automation system designed to modernize poultry waste processing into organic fertilizer.  
It integrates embedded systems, industrial automation, and mobile IoT monitoring for real-time control and remote supervision.

---

## 🚀 Key Features

- **Dual-Microcontroller Architecture**  
  Uses ESP32 and ESP32-S3 for distributed processing of sensors and actuators.

- **Automated Thermal Control System**  
  Maintains temperature between **120°C – 130°C** using MAX6675 thermocouples and SSR-based control.

- **Industrial Motor Automation**  
  Controls conveyor and pulverizer systems with automatic and manual override modes.

- **IoT Cloud Integration**  
  Real-time data logging using Firebase Realtime Database.

- **Remote Monitoring System**  
  Android application for live dashboard, monitoring, and manual control.

---

## 🛠️ System Architecture

The system is divided into multiple layers:

- **Sensing Layer:**  
  Reads temperature data using MAX6675 thermocouples.

- **Control Layer:**  
  ESP32 master controller manages heating via SSR.

- **Actuation Layer:**  
  Secondary controller handles conveyor and pulverizer motors.

- **Cloud Layer:**  
  Sends real-time data to Firebase Realtime Database.

- **Application Layer:**  
  Android app provides monitoring and manual override control.

---

## 🧰 Technical Stack

### Hardware
- ESP32-WROOM-32  
- ESP32-S3  
- MAX6675 Thermocouple Module  
- Solid State Relays (SSR)    
- Industrial Motors  
- Heating Elements  
- I2C LCD Display  
- Switching Power Supply & Buck Converters  

### Software
- **Mobile App:** Kotlin (Android Studio)  
- **Firmware:** C++ (Arduino Framework)  
- **Backend:** Firebase Realtime Database  
- **Design Tools:** Android Studio 

---

## 📱 Mobile Application

The Android application includes:
- Real-time temperature monitoring  
- System status dashboard  
- Manual control override  
- Firebase synchronization  

---

## 📸 Project Visuals

<img width="717" height="1600" alt="WhatsApp Image 2026-06-01 at 17 48 28" src="https://github.com/user-attachments/assets/56ea333d-4e99-41e9-abcf-d195bb1c3bfa" />
<img width="1080" height="2408" alt="WhatsApp Image 2026-06-01 at 17 48 28 (1)" src="https://github.com/user-attachments/assets/51a6a20e-b1f7-442f-987d-10a0e6d036e2" />
<img width="717" height="1600" alt="WhatsApp Image 2026-06-01 at 17 48 28 (2)" src="https://github.com/user-attachments/assets/c31e27d5-eb98-4789-9f32-68b472d9518b" />
<img width="717" height="1600" alt="WhatsApp Image 2026-06-01 at 17 48 28 (3)" src="https://github.com/user-attachments/assets/b532c527-7b05-47fb-ad40-5cf6db00acde" />
<img width="717" height="1600" alt="WhatsApp Image 2026-06-01 at 17 48 29" src="https://github.com/user-attachments/assets/d8078ccc-9485-4bc4-a8a3-f246763e95f5" />






```markdown
![Wiring Diagram](images/wiring-diagram.png)
![Android App Screenshot](images/app-screenshot.png)
