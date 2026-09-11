#pragma once

// ── Display (8-bit parallel) ──────────────────────────────────────────────────
#define PIN_DISP_DB0 17
#define PIN_DISP_DB1 38
#define PIN_DISP_DB2 39
#define PIN_DISP_DB3 40
#define PIN_DISP_DB4 41
#define PIN_DISP_DB5 42
#define PIN_DISP_DB6 2
#define PIN_DISP_DB7 1
#define PIN_DISP_CS 5
#define PIN_DISP_RST 6
#define PIN_DISP_DC 7 // D/C  (RS in datasheet)
#define PIN_DISP_WR 15
#define PIN_DISP_RD 16

// ── Heart Rate Sensor (MAX30101) ─────────────────────────────────────────────
// Sits behind a logic-level-shifter; I2C address 0x57
#define PIN_HRS_SDA 9
#define PIN_HRS_SCL 10
#define PIN_HRS_INT 12
#define PIN_LLS_EN 11 // Logic-level-shifter enable (active HIGH)
#define PIN_HRS_18V 8 // PWM-filtered 1.8 V reference output
// IO8 = HRS_1.8V reference – driven via ledc in main.cpp

// ── Proximity Sensor (VCNL4040) ──────────────────────────────────────────────
// I2C address 0x60
#define PIN_PROX_SDA 44
#define PIN_PROX_SCL 43
#define PIN_PROX_INT 14

// ── IMU (LSM6DSRX) ───────────────────────────────────────────────────────────
#define PIN_IMU_SDA 48
#define PIN_IMU_SCL 45
// #define PIN_IMU_INT 47

// ── Misc ─────────────────────────────────────────────────────────────────────
#define PIN_UI_BUTTON 3
#define PIN_BATT_IN 4 // ADC input for battery voltage divider
#define PIN_BUZZER 18
#define PIN_BOOT_BTN 46 // GPIO0 / boot button
