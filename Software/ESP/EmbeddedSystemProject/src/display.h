#pragma once
#include <Arduino.h>
#include "pins.h"

// ── Dimensions ────────────────────────────────────────────────────────────────
#define DISP_WIDTH 128
#define DISP_HEIGHT 160

// ── Basic 16-bit (RGB565) colour helpers ──────────────────────────────────────
#define RGB565(r, g, b) ((uint16_t)(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)))
#define COL_BLACK 0x0000
#define COL_WHITE 0xFFFF
#define COL_RED RGB565(255, 0, 0)
#define COL_GREEN RGB565(0, 255, 0)
#define COL_BLUE RGB565(0, 0, 255)
#define COL_CYAN RGB565(0, 255, 255)
#define COL_YELLOW RGB565(255, 255, 0)
#define COL_MAGENTA RGB565(255, 0, 255)
#define COL_GRAY RGB565(128, 128, 128)

void disp_init(void);
void disp_set_min_update_interval(uint16_t ms); // 0 = unthrottled (default). e.g. 200 caps updates to 5Hz.
void disp_fill(uint16_t colour);
void disp_draw_pixel(int16_t x, int16_t y, uint16_t colour);
void disp_fill_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint16_t colour);
void disp_draw_char(int16_t x, int16_t y, char c, uint16_t fg, uint16_t bg, uint8_t size);
void disp_draw_string(int16_t x, int16_t y, const char *s, uint16_t fg, uint16_t bg, uint8_t size);
