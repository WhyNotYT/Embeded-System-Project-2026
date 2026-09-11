#include "display.h"
#include <string.h>

// ── Minimal 5x7 font (ASCII 32-127) ──────────────────────────────────────────
// Each character: 5 columns, 8 rows (1 bit per row, LSB = top)
static const uint8_t FONT5X7[][5] = {
    {0x00, 0x00, 0x00, 0x00, 0x00}, // ' '
    {0x00, 0x00, 0x5F, 0x00, 0x00}, // '!'
    {0x00, 0x07, 0x00, 0x07, 0x00}, // '"'
    {0x14, 0x7F, 0x14, 0x7F, 0x14}, // '#'
    {0x24, 0x2A, 0x7F, 0x2A, 0x12}, // '$'
    {0x23, 0x13, 0x08, 0x64, 0x62}, // '%'
    {0x36, 0x49, 0x55, 0x22, 0x50}, // '&'
    {0x00, 0x05, 0x03, 0x00, 0x00}, // '''
    {0x00, 0x1C, 0x22, 0x41, 0x00}, // '('
    {0x00, 0x41, 0x22, 0x1C, 0x00}, // ')'
    {0x14, 0x08, 0x3E, 0x08, 0x14}, // '*'
    {0x08, 0x08, 0x3E, 0x08, 0x08}, // '+'
    {0x00, 0x50, 0x30, 0x00, 0x00}, // ','
    {0x08, 0x08, 0x08, 0x08, 0x08}, // '-'
    {0x00, 0x60, 0x60, 0x00, 0x00}, // '.'
    {0x20, 0x10, 0x08, 0x04, 0x02}, // '/'
    {0x3E, 0x51, 0x49, 0x45, 0x3E}, // '0'
    {0x00, 0x42, 0x7F, 0x40, 0x00}, // '1'
    {0x42, 0x61, 0x51, 0x49, 0x46}, // '2'
    {0x21, 0x41, 0x45, 0x4B, 0x31}, // '3'
    {0x18, 0x14, 0x12, 0x7F, 0x10}, // '4'
    {0x27, 0x45, 0x45, 0x45, 0x39}, // '5'
    {0x3C, 0x4A, 0x49, 0x49, 0x30}, // '6'
    {0x01, 0x71, 0x09, 0x05, 0x03}, // '7'
    {0x36, 0x49, 0x49, 0x49, 0x36}, // '8'
    {0x06, 0x49, 0x49, 0x29, 0x1E}, // '9'
    {0x00, 0x36, 0x36, 0x00, 0x00}, // ':'
    {0x00, 0x56, 0x36, 0x00, 0x00}, // ';'
    {0x08, 0x14, 0x22, 0x41, 0x00}, // '<'
    {0x14, 0x14, 0x14, 0x14, 0x14}, // '='
    {0x00, 0x41, 0x22, 0x14, 0x08}, // '>'
    {0x02, 0x01, 0x51, 0x09, 0x06}, // '?'
    {0x32, 0x49, 0x79, 0x41, 0x3E}, // '@'
    {0x7E, 0x11, 0x11, 0x11, 0x7E}, // 'A'
    {0x7F, 0x49, 0x49, 0x49, 0x36}, // 'B'
    {0x3E, 0x41, 0x41, 0x41, 0x22}, // 'C'
    {0x7F, 0x41, 0x41, 0x22, 0x1C}, // 'D'
    {0x7F, 0x49, 0x49, 0x49, 0x41}, // 'E'
    {0x7F, 0x09, 0x09, 0x09, 0x01}, // 'F'
    {0x3E, 0x41, 0x49, 0x49, 0x7A}, // 'G'
    {0x7F, 0x08, 0x08, 0x08, 0x7F}, // 'H'
    {0x00, 0x41, 0x7F, 0x41, 0x00}, // 'I'
    {0x20, 0x40, 0x41, 0x3F, 0x01}, // 'J'
    {0x7F, 0x08, 0x14, 0x22, 0x41}, // 'K'
    {0x7F, 0x40, 0x40, 0x40, 0x40}, // 'L'
    {0x7F, 0x02, 0x0C, 0x02, 0x7F}, // 'M'
    {0x7F, 0x04, 0x08, 0x10, 0x7F}, // 'N'
    {0x3E, 0x41, 0x41, 0x41, 0x3E}, // 'O'
    {0x7F, 0x09, 0x09, 0x09, 0x06}, // 'P'
    {0x3E, 0x41, 0x51, 0x21, 0x5E}, // 'Q'
    {0x7F, 0x09, 0x19, 0x29, 0x46}, // 'R'
    {0x46, 0x49, 0x49, 0x49, 0x31}, // 'S'
    {0x01, 0x01, 0x7F, 0x01, 0x01}, // 'T'
    {0x3F, 0x40, 0x40, 0x40, 0x3F}, // 'U'
    {0x1F, 0x20, 0x40, 0x20, 0x1F}, // 'V'
    {0x3F, 0x40, 0x38, 0x40, 0x3F}, // 'W'
    {0x63, 0x14, 0x08, 0x14, 0x63}, // 'X'
    {0x07, 0x08, 0x70, 0x08, 0x07}, // 'Y'
    {0x61, 0x51, 0x49, 0x45, 0x43}, // 'Z'
    {0x00, 0x7F, 0x41, 0x41, 0x00}, // '['
    {0x02, 0x04, 0x08, 0x10, 0x20}, // '\'
    {0x00, 0x41, 0x41, 0x7F, 0x00}, // ']'
    {0x04, 0x02, 0x01, 0x02, 0x04}, // '^'
    {0x40, 0x40, 0x40, 0x40, 0x40}, // '_'
    {0x00, 0x01, 0x02, 0x04, 0x00}, // '`'
    {0x20, 0x54, 0x54, 0x54, 0x78}, // 'a'
    {0x7F, 0x48, 0x44, 0x44, 0x38}, // 'b'
    {0x38, 0x44, 0x44, 0x44, 0x20}, // 'c'
    {0x38, 0x44, 0x44, 0x48, 0x7F}, // 'd'
    {0x38, 0x54, 0x54, 0x54, 0x18}, // 'e'
    {0x08, 0x7E, 0x09, 0x01, 0x02}, // 'f'
    {0x0C, 0x52, 0x52, 0x52, 0x3E}, // 'g'
    {0x7F, 0x08, 0x04, 0x04, 0x78}, // 'h'
    {0x00, 0x44, 0x7D, 0x40, 0x00}, // 'i'
    {0x20, 0x40, 0x44, 0x3D, 0x00}, // 'j'
    {0x7F, 0x10, 0x28, 0x44, 0x00}, // 'k'
    {0x00, 0x41, 0x7F, 0x40, 0x00}, // 'l'
    {0x7C, 0x04, 0x18, 0x04, 0x78}, // 'm'
    {0x7C, 0x08, 0x04, 0x04, 0x78}, // 'n'
    {0x38, 0x44, 0x44, 0x44, 0x38}, // 'o'
    {0x7C, 0x14, 0x14, 0x14, 0x08}, // 'p'
    {0x08, 0x14, 0x14, 0x18, 0x7C}, // 'q'
    {0x7C, 0x08, 0x04, 0x04, 0x08}, // 'r'
    {0x48, 0x54, 0x54, 0x54, 0x20}, // 's'
    {0x04, 0x3F, 0x44, 0x40, 0x20}, // 't'
    {0x3C, 0x40, 0x40, 0x20, 0x7C}, // 'u'
    {0x1C, 0x20, 0x40, 0x20, 0x1C}, // 'v'
    {0x3C, 0x40, 0x30, 0x40, 0x3C}, // 'w'
    {0x44, 0x28, 0x10, 0x28, 0x44}, // 'x'
    {0x0C, 0x50, 0x50, 0x50, 0x3C}, // 'y'
    {0x44, 0x64, 0x54, 0x4C, 0x44}, // 'z'
    {0x00, 0x08, 0x36, 0x41, 0x00}, // '{'
    {0x00, 0x00, 0x7F, 0x00, 0x00}, // '|'
    {0x00, 0x41, 0x36, 0x08, 0x00}, // '}'
    {0x10, 0x08, 0x08, 0x10, 0x08}, // '~'
    {0x78, 0x46, 0x41, 0x46, 0x78}, // DEL (placeholder)
};

// ── Low-level parallel bus helpers ────────────────────────────────────────────

static inline void write_bus(uint8_t d)
{
  // DB[0..7] spread across: DB0=17 DB1=38 DB2=39 DB3=40 DB4=41 DB5=42 DB6=2 DB7=1
  // (pins.h previously had DB1/DB2 on 44/43, which collide with UART0
  // TX/RX used by Serial for debug logging -- fixed to match this comment)
  // Drive each bit individually — fast enough for setup/text rendering
  digitalWrite(PIN_DISP_DB0, (d >> 0) & 1);
  digitalWrite(PIN_DISP_DB1, (d >> 1) & 1);
  digitalWrite(PIN_DISP_DB2, (d >> 2) & 1);
  digitalWrite(PIN_DISP_DB3, (d >> 3) & 1);
  digitalWrite(PIN_DISP_DB4, (d >> 4) & 1);
  digitalWrite(PIN_DISP_DB5, (d >> 5) & 1);
  digitalWrite(PIN_DISP_DB6, (d >> 6) & 1);
  digitalWrite(PIN_DISP_DB7, (d >> 7) & 1);
}

static inline void pulse_wr(void)
{
  digitalWrite(PIN_DISP_WR, LOW);
  // ~10 ns pulse minimum for ST7735; digitalWrite already slow enough
  digitalWrite(PIN_DISP_WR, HIGH);
}

static void write_cmd(uint8_t cmd)
{
  digitalWrite(PIN_DISP_DC, LOW); // command
  digitalWrite(PIN_DISP_CS, LOW);
  write_bus(cmd);
  pulse_wr();
  digitalWrite(PIN_DISP_CS, HIGH);
}

static void write_dat(uint8_t dat)
{
  digitalWrite(PIN_DISP_DC, HIGH); // data
  digitalWrite(PIN_DISP_CS, LOW);
  write_bus(dat);
  pulse_wr();
  digitalWrite(PIN_DISP_CS, HIGH);
}

// Emit one pixel in the manufacturer's 18-bit-per-pixel format: three
// bytes per pixel, order B, G, R (see disp3()'s data(0x00/0xFF) triples
// in the reference .ino). Caller must already hold CS low / DC high.
// Expands a 16-bit 5-6-5 colour (our COL_* macros) into that 3-byte form
// so the rest of the code can keep using the existing colour constants.
static inline void write_pixel_from_565(uint16_t colour565)
{
  uint8_t r5 = (colour565 >> 11) & 0x1F;
  uint8_t g6 = (colour565 >> 5) & 0x3F;
  uint8_t b5 = colour565 & 0x1F;

  // Scale up to 8-bit-ish values the same way the reference's raw
  // 0x00/0xFF full-channel writes do at the extremes.
  uint8_t r8 = (r5 << 3) | (r5 >> 2);
  uint8_t g8 = (g6 << 2) | (g6 >> 4);
  uint8_t b8 = (b5 << 3) | (b5 >> 2);

  write_bus(b8);
  pulse_wr();
  write_bus(g8);
  pulse_wr();
  write_bus(r8);
  pulse_wr();
}

// ── Public API ────────────────────────────────────────────────────────────────

void disp_init(void)
{
  // Configure data bus pins as outputs
  const uint8_t BUS_PINS[] = {
      PIN_DISP_DB0, PIN_DISP_DB1, PIN_DISP_DB2, PIN_DISP_DB3,
      PIN_DISP_DB4, PIN_DISP_DB5, PIN_DISP_DB6, PIN_DISP_DB7};
  for (uint8_t p : BUS_PINS)
    pinMode(p, OUTPUT);

  // Control pins
  pinMode(PIN_DISP_CS, OUTPUT);
  pinMode(PIN_DISP_RST, OUTPUT);
  pinMode(PIN_DISP_DC, OUTPUT);
  pinMode(PIN_DISP_WR, OUTPUT);
  pinMode(PIN_DISP_RD, OUTPUT);

  // RD kept HIGH (we never read the display)
  digitalWrite(PIN_DISP_RD, HIGH);
  digitalWrite(PIN_DISP_WR, HIGH);
  digitalWrite(PIN_DISP_CS, HIGH);

  // Hardware reset
  digitalWrite(PIN_DISP_RST, LOW);
  delay(10);
  digitalWrite(PIN_DISP_RST, HIGH);
  delay(120);

  // Initialisation sequence — matches Newhaven's reference .ino exactly.
  write_cmd(0x11); // SLPOUT
  delay(120);

  write_cmd(0x28); // DISPOFF (per manufacturer sequence, before setup)

  write_cmd(0x26);
  write_dat(0x04); // GAMSET

  // FRMCTR1 (Frame Rate Control, normal mode). Values match Newhaven's
  // own reference sequence unchanged (DIVA=0x0A, RTNA=0x14).
  write_cmd(0xB1);
  write_dat(0x0A);
  write_dat(0x14); // FRMCTR1
  write_cmd(0xC0);
  write_dat(0x0A);
  write_dat(0x00); // PWCTR1
  write_cmd(0xC1);
  write_dat(0x02); // PWCTR2
  write_cmd(0xC5);
  write_dat(0x2F);
  write_dat(0x3E); // VMCTR1
  write_cmd(0xC7);
  write_dat(0x40); // VMCTR2

  // Column address set (0-127)
  write_cmd(0x2A);
  write_dat(0x00);
  write_dat(0x00);
  write_dat(0x00);
  write_dat(0x7F);

  // Row address set (0-159)
  write_cmd(0x2B);
  write_dat(0x00);
  write_dat(0x00);
  write_dat(0x00);
  write_dat(0x9F);

  write_cmd(0x36);
  write_dat(0xC8); // MADCTL: MY, MX, BGR (matches manufacturer)
  write_cmd(0x3A);
  write_dat(0x06); // COLMOD: 18-bit colour (manufacturer default)

  write_cmd(0x29); // DISPON
  delay(10);

  disp_fill(COL_BLACK);
}

// ── Update-rate limiting ─────────────────────────────────────────────────
// This does NOT change the ILI9163V's own internal panel-scan rate (that's
// FRMCTR1 above) -- it caps how often *we* are willing to burst new pixel
// data at it. Every draw call is a burst of rapid GPIO switching; capping
// how often those bursts can happen directly caps how often the associated
// current draw happens, independent of any register-level uncertainty.
static uint16_t s_minUpdateIntervalMs = 0; // 0 = unthrottled (default)
static uint32_t s_lastUpdateMs = 0;

void disp_set_min_update_interval(uint16_t ms)
{
  s_minUpdateIntervalMs = ms;
}

static void disp_throttle(void)
{
  if (s_minUpdateIntervalMs == 0)
    return;
  uint32_t elapsed = millis() - s_lastUpdateMs;
  if (elapsed < s_minUpdateIntervalMs)
  {
    delay(s_minUpdateIntervalMs - elapsed);
  }
  s_lastUpdateMs = millis();
}

// Set address window for subsequent RAMWR writes
static void set_addr_window(int16_t x0, int16_t y0, int16_t x1, int16_t y1)
{
  write_cmd(0x2A);
  write_dat(0x00);
  write_dat((uint8_t)x0);
  write_dat(0x00);
  write_dat((uint8_t)x1);

  write_cmd(0x2B);
  write_dat(0x00);
  write_dat((uint8_t)y0);
  write_dat(0x00);
  write_dat((uint8_t)y1);

  write_cmd(0x2C); // RAMWR
}

void disp_fill_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint16_t colour)
{
  if (x >= DISP_WIDTH || y >= DISP_HEIGHT || w == 0 || h == 0)
    return;
  if (x + w > DISP_WIDTH)
    w = DISP_WIDTH - x;
  if (y + h > DISP_HEIGHT)
    h = DISP_HEIGHT - y;

  disp_throttle();
  set_addr_window(x, y, x + w - 1, y + h - 1);

  digitalWrite(PIN_DISP_DC, HIGH);
  digitalWrite(PIN_DISP_CS, LOW);
  for (int32_t i = (int32_t)w * h; i > 0; i--)
  {
    write_pixel_from_565(colour);
  }
  digitalWrite(PIN_DISP_CS, HIGH);
}

void disp_fill(uint16_t colour)
{
  disp_fill_rect(0, 0, DISP_WIDTH, DISP_HEIGHT, colour);
}

void disp_draw_pixel(int16_t x, int16_t y, uint16_t colour)
{
  disp_fill_rect(x, y, 1, 1, colour);
}

void disp_draw_char(int16_t x, int16_t y, char c, uint16_t fg, uint16_t bg, uint8_t size)
{
  if (c < 32 || c > 127)
    c = '?';
  const uint8_t *glyph = FONT5X7[c - 32];

  // Full glyph cell, including the 1-pixel gap column, in one shot.
  int16_t cellW = 6 * size;
  int16_t cellH = 8 * size;

  if (x >= DISP_WIDTH || y >= DISP_HEIGHT)
    return;
  int16_t w = cellW, h = cellH;
  if (x + w > DISP_WIDTH)
    w = DISP_WIDTH - x;
  if (y + h > DISP_HEIGHT)
    h = DISP_HEIGHT - y;

  disp_throttle();

  // Set the address window ONCE for the whole character instead of once
  // per pixel. Previously every pixel re-issued a full 0x2A/0x2B/0x2C
  // command sequence (CS toggled ~12x per pixel), which meant drawing a
  // single 5x7 glyph fired off thousands of rapid GPIO bursts. That bursty
  // switching pattern is a classic cause of visible supply sag on boards
  // with marginal decoupling — which shows up as the backlight flickering
  // in sync with anything being drawn.
  set_addr_window(x, y, x + w - 1, y + h - 1);

  digitalWrite(PIN_DISP_DC, HIGH);
  digitalWrite(PIN_DISP_CS, LOW);
  for (int16_t row = 0; row < h; row++)
  {
    uint8_t glyphRow = row / size;
    for (int16_t col = 0; col < w; col++)
    {
      uint16_t colour = bg;
      if (col < 5 * size)
      {
        uint8_t glyphCol = col / size;
        colour = (glyph[glyphCol] & (1 << glyphRow)) ? fg : bg;
      }
      write_pixel_from_565(colour);
    }
  }
  digitalWrite(PIN_DISP_CS, HIGH);
}

void disp_draw_string(int16_t x, int16_t y, const char *s, uint16_t fg, uint16_t bg, uint8_t size)
{
  int16_t cx = x;
  while (*s)
  {
    disp_draw_char(cx, y, *s++, fg, bg, size);
    cx += 6 * size;
    if (cx + 6 * size > DISP_WIDTH)
    {
      cx = x;
      y += 9 * size;
    }
  }
}