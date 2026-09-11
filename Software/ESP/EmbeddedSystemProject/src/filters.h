/*!
 * @file filters.h
 * Minimal single-pole IIR filters, a discrete differentiator, and a
 * circular moving-average filter used by the PPG heart-rate detector
 * in main.cpp. This is a local, project-owned header (the devxplained
 * MAX3010x library does not ship a filters.h).
 */
#pragma once

#include <math.h>

#ifndef PI
#define PI 3.14159265358979323846f
#endif

// ── Single-pole low-pass IIR filter ───────────────────────────────────────────
class LowPassFilter
{
public:
  LowPassFilter(float cutoff_freq, float sampling_freq)
  {
    float rc = 1.0f / (2.0f * PI * cutoff_freq);
    float dt = 1.0f / sampling_freq;
    _alpha = dt / (rc + dt);
    reset();
  }

  float process(float value)
  {
    if (isnan(_prev))
    {
      _prev = value;
    }
    _prev += _alpha * (value - _prev);
    return _prev;
  }

  void reset() { _prev = NAN; }

private:
  float _alpha;
  float _prev;
};

// ── Single-pole high-pass IIR filter ──────────────────────────────────────────
class HighPassFilter
{
public:
  HighPassFilter(float cutoff_freq, float sampling_freq)
  {
    float rc = 1.0f / (2.0f * PI * cutoff_freq);
    float dt = 1.0f / sampling_freq;
    _alpha = rc / (rc + dt);
    reset();
  }

  float process(float value)
  {
    if (isnan(_prevIn))
    {
      _prevIn = value;
      _prevOut = 0.0f;
      return 0.0f;
    }
    float out = _alpha * (_prevOut + value - _prevIn);
    _prevIn = value;
    _prevOut = out;
    return out;
  }

  void reset()
  {
    _prevIn = NAN;
    _prevOut = 0.0f;
  }

private:
  float _alpha;
  float _prevIn;
  float _prevOut;
};

// ── First-order discrete differentiator ───────────────────────────────────────
class Differentiator
{
public:
  explicit Differentiator(float sampling_freq) : _dt(1.0f / sampling_freq) { reset(); }

  float process(float value)
  {
    float diff = NAN;
    if (!isnan(_prev))
    {
      diff = (value - _prev) / _dt;
    }
    _prev = value;
    return diff;
  }

  void reset() { _prev = NAN; }

private:
  float _dt;
  float _prev;
};

// ── Circular moving-average filter ────────────────────────────────────────────
template <int N>
class MovingAverageFilter
{
public:
  MovingAverageFilter() { reset(); }

  int process(int value)
  {
    _sum -= _buf[_idx];
    _buf[_idx] = value;
    _sum += value;
    _idx = (_idx + 1) % N;
    if (_count < N)
      _count++;
    return (int)(_sum / _count);
  }

  void reset()
  {
    for (int i = 0; i < N; i++)
      _buf[i] = 0;
    _sum = 0;
    _idx = 0;
    _count = 0;
  }

  int count() const { return _count; }

private:
  int _buf[N];
  long _sum;
  int _idx;
  int _count;
};