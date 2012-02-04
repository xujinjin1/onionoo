/* Copyright 2011, 2012 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.onionoo;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;

import org.torproject.descriptor.BridgeNetworkStatus;
import org.torproject.descriptor.Descriptor;
import org.torproject.descriptor.DescriptorFile;
import org.torproject.descriptor.DescriptorReader;
import org.torproject.descriptor.DescriptorSourceFactory;
import org.torproject.descriptor.NetworkStatusEntry;
import org.torproject.descriptor.RelayNetworkStatusConsensus;

import com.maxmind.geoip.LookupService;

/* Store relays and bridges that have been running in the past seven
 * days. */
public class CurrentNodes {

  private long lastValidAfterMillis = 0L;
  private long lastPublishedMillis = 0L;

  private long cutoff = System.currentTimeMillis()
      - 7L * 24L * 60L * 60L * 1000L;

  public void readRelayNetworkConsensuses() {
    DescriptorReader reader =
        DescriptorSourceFactory.createDescriptorReader();
    reader.addDirectory(new File("in/relay-descriptors/consensuses"));
    reader.setExcludeFiles(new File("status/relay-consensus-history"));
    Iterator<DescriptorFile> descriptorFiles = reader.readDescriptors();
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      if (descriptorFile.getDescriptors() != null) {
        for (Descriptor descriptor : descriptorFile.getDescriptors()) {
          updateRelayNetworkStatusConsensus((RelayNetworkStatusConsensus)
              descriptor);
        }
      }
    }
    if (this.lastValidAfterMillis > 0L) {
      for (Node entry : this.currentRelays.values()) {
        entry.setRunning(entry.getLastSeenMillis() ==
            this.lastValidAfterMillis);
      }
    }
  }

  private void updateRelayNetworkStatusConsensus(
      RelayNetworkStatusConsensus consensus) {
    long validAfterMillis = consensus.getValidAfterMillis();
    for (NetworkStatusEntry entry :
        consensus.getStatusEntries().values()) {
      String nickname = entry.getNickname();
      String fingerprint = entry.getFingerprint();
      String address = entry.getAddress();
      int orPort = entry.getOrPort();
      int dirPort = entry.getDirPort();
      SortedSet<String> relayFlags = entry.getFlags();
      this.addRelay(nickname, fingerprint, address, validAfterMillis,
          orPort, dirPort, relayFlags);
    }
  }

  public void addRelay(String nickname, String fingerprint,
      String address, long validAfterMillis, int orPort, int dirPort,
      SortedSet<String> relayFlags) {
    if (validAfterMillis >= cutoff &&
        (!this.currentRelays.containsKey(fingerprint) ||
        this.currentRelays.get(fingerprint).getLastSeenMillis() <
        validAfterMillis)) {
      Node entry = new Node(nickname, fingerprint, address,
          validAfterMillis, orPort, dirPort, relayFlags);
      this.currentRelays.put(fingerprint, entry);
      if (validAfterMillis > this.lastValidAfterMillis) {
        this.lastValidAfterMillis = validAfterMillis;
      }
    }
  }

  public void lookUpCountries() {
    File geoipDatFile = new File("GeoIP.dat");
    if (!geoipDatFile.exists()) {
      System.err.println("No GeoIP.dat file in /.");
      return;
    }
    try {
      LookupService ls = new LookupService(geoipDatFile,
          LookupService.GEOIP_MEMORY_CACHE);
      for (Node relay : currentRelays.values()) {
        String country = ls.getCountry(relay.getAddress()).getCode();
        if (country != null) {
          relay.setCountry(country.toLowerCase());
        }
      }
      ls.close();
    } catch (IOException e) {
      System.err.println("Could not look up countries for relays.");
    }
  }

  public void readBridgeNetworkStatuses() {
    DescriptorReader reader =
        DescriptorSourceFactory.createDescriptorReader();
    reader.addDirectory(new File("in/bridge-descriptors/statuses"));
    reader.setExcludeFiles(new File("status/bridge-status-history"));
    Iterator<DescriptorFile> descriptorFiles = reader.readDescriptors();
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      if (descriptorFile.getDescriptors() != null) {
        for (Descriptor descriptor : descriptorFile.getDescriptors()) {
          updateBridgeNetworkStatus((BridgeNetworkStatus) descriptor);
        }
      }
    }
    if (this.lastPublishedMillis > 0L) {
      for (Node entry : this.currentBridges.values()) {
        entry.setRunning(entry.getLastSeenMillis() ==
            this.lastPublishedMillis);
      }
    }
  }

  private void updateBridgeNetworkStatus(BridgeNetworkStatus status) {
    long publishedMillis = status.getPublishedMillis();
    for (NetworkStatusEntry entry : status.getStatusEntries().values()) {
      String fingerprint = entry.getFingerprint();
      String address = entry.getAddress();
      int orPort = entry.getOrPort();
      int dirPort = entry.getDirPort();
      SortedSet<String> relayFlags = entry.getFlags();
      this.addBridge(fingerprint, address, publishedMillis, orPort,
         dirPort, relayFlags);
    }
  }

  public void addBridge(String fingerprint, String address,
      long publishedMillis, int orPort, int dirPort,
      SortedSet<String> relayFlags) {
    if (publishedMillis >= cutoff &&
        (!this.currentBridges.containsKey(fingerprint) ||
        this.currentBridges.get(fingerprint).getLastSeenMillis() <
        publishedMillis)) {
      Node entry = new Node("Unnamed", fingerprint, address,
          publishedMillis, orPort, dirPort, relayFlags);
      this.currentBridges.put(fingerprint, entry);
      if (publishedMillis > this.lastPublishedMillis) {
        this.lastPublishedMillis = publishedMillis;
      }
    }
  }

  private SortedMap<String, Node> currentRelays =
      new TreeMap<String, Node>();
  public SortedMap<String, Node> getCurrentRelays() {
    return new TreeMap<String, Node>(this.currentRelays);
  }

  private SortedMap<String, Node> currentBridges =
      new TreeMap<String, Node>();
  public SortedMap<String, Node> getCurrentBridges() {
    return new TreeMap<String, Node>(this.currentBridges);
  }

  public long getLastValidAfterMillis() {
    return this.lastValidAfterMillis;
  }

  public long getLastPublishedMillis() {
    return this.lastPublishedMillis;
  }
}

