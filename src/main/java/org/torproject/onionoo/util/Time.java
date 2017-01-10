/* Copyright 2013--2017 The Tor Project
 * See LICENSE for licensing information */

package org.torproject.onionoo.util;

/*
 * Wrapper for System.currentTimeMillis() that can be replaced with a
 * custom time source for testing.
 *
 * @deprecated Try to use other time setting methods for testing.
 */
public class Time {

  public long currentTimeMillis() {
    return System.currentTimeMillis();
  }
}

