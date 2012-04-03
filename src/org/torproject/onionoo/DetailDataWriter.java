/* Copyright 2011, 2012 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.onionoo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.lang.StringEscapeUtils;

import org.torproject.descriptor.BridgePoolAssignment;
import org.torproject.descriptor.Descriptor;
import org.torproject.descriptor.DescriptorFile;
import org.torproject.descriptor.DescriptorReader;
import org.torproject.descriptor.DescriptorSourceFactory;
import org.torproject.descriptor.ExitList;
import org.torproject.descriptor.ExitListEntry;
import org.torproject.descriptor.ServerDescriptor;

/* Write updated detail data files to disk and delete files of relays or
 * bridges that fell out of the summary list.
 *
 * The parts of details files coming from server descriptors always come
 * from the last known descriptor of a relay or bridge, not from the
 * descriptor that was last referenced in a network status. */
public class DetailDataWriter {

  private SortedMap<String, Node> relays;
  public void setCurrentRelays(SortedMap<String, Node> currentRelays) {
    this.relays = currentRelays;
  }

  private SortedMap<String, Node> bridges;
  public void setCurrentBridges(SortedMap<String, Node> currentBridges) {
    this.bridges = currentBridges;
  }

  private Map<String, ServerDescriptor> relayServerDescriptors =
      new HashMap<String, ServerDescriptor>();
  public void readRelayServerDescriptors() {
    DescriptorReader reader =
        DescriptorSourceFactory.createDescriptorReader();
    reader.addDirectory(new File(
        "in/relay-descriptors/server-descriptors"));
    /* Don't remember which server descriptors we already parsed.  If we
     * parse a server descriptor now and first learn about the relay in a
     * later consensus, we'll never write the descriptor content anywhere.
     * The result would be details files containing no descriptor parts
     * until the relay publishes the next descriptor. */
    Iterator<DescriptorFile> descriptorFiles = reader.readDescriptors();
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      if (descriptorFile.getDescriptors() != null) {
        for (Descriptor descriptor : descriptorFile.getDescriptors()) {
          if (descriptor instanceof ServerDescriptor) {
            ServerDescriptor serverDescriptor =
                (ServerDescriptor) descriptor;
            String fingerprint = serverDescriptor.getFingerprint();
            if (!this.relayServerDescriptors.containsKey(fingerprint) ||
                this.relayServerDescriptors.get(fingerprint).
                getPublishedMillis()
                < serverDescriptor.getPublishedMillis()) {
              this.relayServerDescriptors.put(fingerprint,
                  serverDescriptor);
            }
          }
        }
      }
    }
  }

  private long now = System.currentTimeMillis();
  private Map<String, Set<ExitListEntry>> exitListEntries =
      new HashMap<String, Set<ExitListEntry>>();
  public void readExitLists() {
    DescriptorReader reader =
        DescriptorSourceFactory.createDescriptorReader();
    reader.addDirectory(new File(
        "in/exit-lists"));
    reader.setExcludeFiles(new File("status/exit-list-history"));
    Iterator<DescriptorFile> descriptorFiles = reader.readDescriptors();
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      if (descriptorFile.getDescriptors() != null) {
        for (Descriptor descriptor : descriptorFile.getDescriptors()) {
          if (descriptor instanceof ExitList) {
            ExitList exitList = (ExitList) descriptor;
            for (ExitListEntry exitListEntry :
                exitList.getExitListEntries()) {
              if (exitListEntry.getScanMillis() <
                  this.now - 24L * 60L * 60L * 1000L) {
                continue;
              }
              String fingerprint = exitListEntry.getFingerprint();
              if (!this.exitListEntries.containsKey(fingerprint)) {
                this.exitListEntries.put(fingerprint,
                    new HashSet<ExitListEntry>());
              }
              this.exitListEntries.get(fingerprint).add(exitListEntry);
            }
          }
        }
      }
    }
  }

  private Map<String, ServerDescriptor> bridgeServerDescriptors =
      new HashMap<String, ServerDescriptor>();
  public void readBridgeServerDescriptors() {
    DescriptorReader reader =
        DescriptorSourceFactory.createDescriptorReader();
    reader.addDirectory(new File(
        "in/bridge-descriptors/server-descriptors"));
    /* Don't remember which server descriptors we already parsed.  If we
     * parse a server descriptor now and first learn about the relay in a
     * later status, we'll never write the descriptor content anywhere.
     * The result would be details files containing no descriptor parts
     * until the bridge publishes the next descriptor. */
    Iterator<DescriptorFile> descriptorFiles = reader.readDescriptors();
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      if (descriptorFile.getDescriptors() != null) {
        for (Descriptor descriptor : descriptorFile.getDescriptors()) {
          if (descriptor instanceof ServerDescriptor) {
            ServerDescriptor serverDescriptor =
                (ServerDescriptor) descriptor;
            String fingerprint = serverDescriptor.getFingerprint();
            if (!this.bridgeServerDescriptors.containsKey(fingerprint) ||
                this.bridgeServerDescriptors.get(fingerprint).
                getPublishedMillis()
                < serverDescriptor.getPublishedMillis()) {
              this.bridgeServerDescriptors.put(fingerprint,
                  serverDescriptor);
            }
          }
        }
      }
    }
  }

  private Map<String, String> bridgePoolAssignments =
      new HashMap<String, String>();
  public void readBridgePoolAssignments() {
    DescriptorReader reader =
        DescriptorSourceFactory.createDescriptorReader();
    reader.addDirectory(new File("in/bridge-pool-assignments"));
    reader.setExcludeFiles(new File("status/bridge-poolassign-history"));
    Iterator<DescriptorFile> descriptorFiles = reader.readDescriptors();
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      if (descriptorFile.getDescriptors() != null) {
        for (Descriptor descriptor : descriptorFile.getDescriptors()) {
          if (descriptor instanceof BridgePoolAssignment) {
            BridgePoolAssignment bridgePoolAssignment =
                (BridgePoolAssignment) descriptor;
            for (Map.Entry<String, String> e :
                bridgePoolAssignment.getEntries().entrySet()) {
              String fingerprint = e.getKey();
              String details = e.getValue();
              this.bridgePoolAssignments.put(fingerprint, details);
            }
          }
        }
      }
    }
  }

  public void writeDetailDataFiles() {
    SortedMap<String, File> remainingDetailsFiles =
        this.listAllDetailsFiles();
    remainingDetailsFiles = this.updateRelayDetailsFiles(
        remainingDetailsFiles);
    remainingDetailsFiles = this.updateBridgeDetailsFiles(
        remainingDetailsFiles);
    this.deleteDetailsFiles(remainingDetailsFiles);
  }

  private File detailsFileDirectory = new File("out/details");
  private SortedMap<String, File> listAllDetailsFiles() {
    SortedMap<String, File> result = new TreeMap<String, File>();
    if (detailsFileDirectory.exists() &&
        detailsFileDirectory.isDirectory()) {
      for (File file : detailsFileDirectory.listFiles()) {
        if (file.getName().length() == 40) {
          result.put(file.getName(), file);
        }
      }
    }
    return result;
  }

  private SortedMap<String, File> updateRelayDetailsFiles(
      SortedMap<String, File> remainingDetailsFiles) {
    SortedMap<String, File> result =
        new TreeMap<String, File>(remainingDetailsFiles);
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    Map<String, Long> relaysByConsensusWeight =
        new HashMap<String, Long>();
    for (Map.Entry<String, Node> relay : this.relays.entrySet()) {
      String fingerprint = relay.getKey();

      /* Read details file for this relay if it exists. */
      String descriptorParts = null;
      long publishedMillis = -1L;
      if (result.containsKey(fingerprint)) {
        File detailsFile = result.remove(fingerprint);
        try {
          BufferedReader br = new BufferedReader(new FileReader(
              detailsFile));
          String line;
          boolean copyDescriptorParts = false;
          StringBuilder sb = new StringBuilder();
          while ((line = br.readLine()) != null) {
            if (line.startsWith("\"desc_published\":")) {
              String published = line.substring(
                  "\"desc_published\":\"".length(),
                  "\"desc_published\":\"1970-01-01 00:00:00".length());
              publishedMillis = dateTimeFormat.parse(published).getTime();
              copyDescriptorParts = true;
            }
            if (copyDescriptorParts) {
              sb.append(line + "\n");
            }
          }
          br.close();
          if (sb.length() > 0) {
            descriptorParts = sb.toString();
          }
        } catch (IOException e) {
          System.err.println("Could not read '"
              + detailsFile.getAbsolutePath() + "'.  Skipping");
          e.printStackTrace();
          publishedMillis = -1L;
          descriptorParts = null;
        } catch (ParseException e) {
          System.err.println("Could not read '"
              + detailsFile.getAbsolutePath() + "'.  Skipping");
          e.printStackTrace();
          publishedMillis = -1L;
          descriptorParts = null;
        }
      }

      /* Generate new descriptor-specific part if we have a more recent
       * descriptor or if the part we read didn't contain a last_restarted
       * line. */
      if (this.relayServerDescriptors.containsKey(fingerprint) &&
          (this.relayServerDescriptors.get(fingerprint).
          getPublishedMillis() > publishedMillis)) {
        ServerDescriptor descriptor = this.relayServerDescriptors.get(
            fingerprint);
        StringBuilder sb = new StringBuilder();
        String publishedDateTime = dateTimeFormat.format(
            descriptor.getPublishedMillis());
        String lastRestartedString = dateTimeFormat.format(
            descriptor.getPublishedMillis()
            - descriptor.getUptime() * 1000L);
        int advertisedBandwidth = Math.min(descriptor.getBandwidthRate(),
            Math.min(descriptor.getBandwidthBurst(),
            descriptor.getBandwidthObserved()));
        sb.append("\"desc_published\":\"" + publishedDateTime + "\",\n"
            + "\"last_restarted\":\"" + lastRestartedString + "\",\n"
            + "\"advertised_bandwidth\":" + advertisedBandwidth + ",\n"
            + "\"exit_policy\":[");
        int written = 0;
        for (String exitPolicyLine : descriptor.getExitPolicyLines()) {
          sb.append((written++ > 0 ? "," : "") + "\n  \"" + exitPolicyLine
              + "\"");
        }
        sb.append("\n]");
        if (descriptor.getContact() != null) {
          sb.append(",\n\"contact\":\""
              + StringEscapeUtils.escapeJavaScript(
              descriptor.getContact()) + "\"");
        }
        if (descriptor.getPlatform() != null) {
          sb.append(",\n\"platform\":\""
              + StringEscapeUtils.escapeJavaScript(
              descriptor.getPlatform()) + "\"");
        }
        if (descriptor.getFamilyEntries() != null) {
          sb.append(",\n\"family\":[");
          written = 0;
          for (String familyEntry : descriptor.getFamilyEntries()) {
            sb.append((written++ > 0 ? "," : "") + "\n  \"" + familyEntry
                + "\"");
          }
          sb.append("\n]");
        }
        sb.append("\n}\n");
        descriptorParts = sb.toString();
      }

      /* Generate network-status-specific part. */
      Node entry = relay.getValue();
      String nickname = entry.getNickname();
      String address = entry.getAddress();
      String running = entry.getRunning() ? "true" : "false";
      int orPort = entry.getOrPort();
      int dirPort = entry.getDirPort();
      String countryCode = entry.getCountryCode();
      String latitude = entry.getLatitude();
      String longitude = entry.getLongitude();
      String countryName = entry.getCountryName();
      String regionName = entry.getRegionName();
      String cityName = entry.getCityName();
      String aSNumber = entry.getASNumber();
      String aSName = entry.getASName();
      long consensusWeight = entry.getConsensusWeight();
      StringBuilder sb = new StringBuilder();
      sb.append("{\"version\":1,\n"
          + "\"nickname\":\"" + nickname + "\",\n"
          + "\"fingerprint\":\"" + fingerprint + "\",\n"
          + "\"or_addresses\":[\"" + address + ":" + orPort + "\"],\n"
          + "\"dir_address\":\"" + address + ":" + dirPort + "\",\n"
          + "\"running\":" + running + ",\n");
      SortedSet<String> relayFlags = entry.getRelayFlags();
      if (!relayFlags.isEmpty()) {
        sb.append("\"flags\":[");
        int written = 0;
        for (String relayFlag : relayFlags) {
          sb.append((written++ > 0 ? "," : "") + "\"" + relayFlag + "\"");
        }
        sb.append("]");
      }
      if (countryCode != null) {
        sb.append(",\n\"country\":\"" + countryCode + "\"");
      }
      if (latitude != null) {
        sb.append(",\n\"latitude\":" + latitude);
      }
      if (longitude != null) {
        sb.append(",\n\"longitude\":" + longitude);
      }
      if (countryName != null) {
        sb.append(",\n\"country_name\":\""
            + StringEscapeUtils.escapeJavaScript(countryName) + "\"");
      }
      if (regionName != null) {
        sb.append(",\n\"region_name\":\""
            + StringEscapeUtils.escapeJavaScript(regionName) + "\"");
      }
      if (cityName != null) {
        sb.append(",\n\"city_name\":\""
            + StringEscapeUtils.escapeJavaScript(cityName) + "\"");
      }
      if (aSNumber != null) {
        sb.append(",\n\"as_number\":\""
            + StringEscapeUtils.escapeJavaScript(aSNumber) + "\"");
      }
      if (aSName != null) {
        sb.append(",\n\"as_name\":\""
            + StringEscapeUtils.escapeJavaScript(aSName) + "\"");
      }
      if (consensusWeight >= 0L) {
        sb.append(",\n\"consensus_weight\":"
            + String.valueOf(consensusWeight));
      }

      /* Add exit addresses if at least one of them is distinct from the
       * onion-routing addresses. */
      SortedSet<String> exitAddresses = new TreeSet<String>();
      if (exitListEntries.containsKey(fingerprint)) {
        for (ExitListEntry exitListEntry :
            exitListEntries.get(fingerprint)) {
          String exitAddress = exitListEntry.getExitAddress();
          if (!exitAddress.equals(address)) {
            exitAddresses.add(exitAddress);
          }
        }
      }
      if (!exitAddresses.isEmpty()) {
        sb.append(",\n\"exit_addresses\":[");
        int written = 0;
        for (String exitAddress : exitAddresses) {
          sb.append((written++ > 0 ? "," : "") + "\"" + exitAddress
              + "\"");
        }
        sb.append("]");
      }
      String statusParts = sb.toString();

      /* Write details file to disk. */
      File detailsFile = new File(detailsFileDirectory, fingerprint);
      try {
        detailsFile.getParentFile().mkdirs();
        BufferedWriter bw = new BufferedWriter(new FileWriter(
            detailsFile));
        bw.write(statusParts);
        if (descriptorParts != null) {
          bw.write(",\n" + descriptorParts);
        } else {
          bw.write("\n}\n");
        }
        bw.close();
      } catch (IOException e) {
        System.err.println("Could not write details file '"
            + detailsFile.getAbsolutePath() + "'.  This file may now be "
            + "broken.  Ignoring.");
        e.printStackTrace();
      }

      /* Remember consensus weight to facilitate ordering of results in
       * the servlet. */
      relaysByConsensusWeight.put(fingerprint, consensusWeight);
    }

    /* Write consensus weights to disk to facilitate ordering of results
     * in the servlet. */
    File relaysByConsensusWeightFile =
        new File("out/relays-by-consensus-weight.csv");
    try {
      relaysByConsensusWeightFile.getParentFile().mkdirs();
      BufferedWriter bw = new BufferedWriter(new FileWriter(
          relaysByConsensusWeightFile));
      for (Map.Entry<String, Long> e :
          relaysByConsensusWeight.entrySet()) {
        String fingerprint = e.getKey();
        long consensusWeight = e.getValue();
        bw.write(fingerprint + "," + String.valueOf(consensusWeight)
            + "\n");
      }
      bw.close();
    } catch (IOException e) {
      System.err.println("Could not write file '"
          + relaysByConsensusWeightFile.getAbsolutePath() + "'.  "
          + "Ordering by consensus_weight may now be broken.  Ignoring.");
      e.printStackTrace();
    }

    /* Return the files that we didn't update. */
    return result;
  }

  private SortedMap<String, File> updateBridgeDetailsFiles(
      SortedMap<String, File> remainingDetailsFiles) {
    SortedMap<String, File> result =
        new TreeMap<String, File>(remainingDetailsFiles);
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    for (Map.Entry<String, Node> bridge : this.bridges.entrySet()) {
      String fingerprint = bridge.getKey();

      /* Read details file for this bridge if it exists. */
      String descriptorParts = null, bridgePoolAssignment = null;
      long publishedMillis = -1L;
      if (result.containsKey(fingerprint)) {
        File detailsFile = result.remove(fingerprint);
        try {
          BufferedReader br = new BufferedReader(new FileReader(
              detailsFile));
          String line;
          boolean copyDescriptorParts = false;
          StringBuilder sb = new StringBuilder();
          while ((line = br.readLine()) != null) {
            if (line.startsWith("\"desc_published\":")) {
              String published = line.substring(
                  "\"desc_published\":\"".length(),
                  "\"desc_published\":\"1970-01-01 00:00:00".length());
              publishedMillis = dateTimeFormat.parse(published).getTime();
              copyDescriptorParts = true;
            } else if (line.startsWith("\"pool_assignment\":")) {
              bridgePoolAssignment = line;
              copyDescriptorParts = false;
            } else if (line.equals("}")) {
              copyDescriptorParts = false;
            }
            if (copyDescriptorParts) {
              sb.append(line + "\n");
            }
          }
          br.close();
          descriptorParts = sb.toString();
          if (descriptorParts.endsWith(",\n")) {
            descriptorParts = descriptorParts.substring(0,
                descriptorParts.length() - 2);
          } else if (descriptorParts.endsWith("\n")) {
            descriptorParts = descriptorParts.substring(0,
                descriptorParts.length() - 1);
          }
        } catch (IOException e) {
          System.err.println("Could not read '"
              + detailsFile.getAbsolutePath() + "'.  Skipping");
          e.printStackTrace();
          publishedMillis = -1L;
          descriptorParts = null;
        } catch (ParseException e) {
          System.err.println("Could not read '"
              + detailsFile.getAbsolutePath() + "'.  Skipping");
          e.printStackTrace();
          publishedMillis = -1L;
          descriptorParts = null;
        }
      }

      /* Generate new descriptor-specific part if we have a more recent
       * descriptor. */
      if (this.bridgeServerDescriptors.containsKey(fingerprint) &&
          this.bridgeServerDescriptors.get(fingerprint).
          getPublishedMillis() > publishedMillis) {
        ServerDescriptor descriptor = this.bridgeServerDescriptors.get(
            fingerprint);
        StringBuilder sb = new StringBuilder();
        String publishedDateTime = dateTimeFormat.format(
            descriptor.getPublishedMillis());
        String lastRestartedString = dateTimeFormat.format(
            descriptor.getPublishedMillis()
            - descriptor.getUptime() * 1000L);
        int advertisedBandwidth = Math.min(descriptor.getBandwidthRate(),
            Math.min(descriptor.getBandwidthBurst(),
            descriptor.getBandwidthObserved()));
        sb.append("\"desc_published\":\"" + publishedDateTime + "\",\n"
            + "\"last_restarted\":\"" + lastRestartedString + "\",\n"
            + "\"advertised_bandwidth\":" + advertisedBandwidth + ",\n"
            + "\"platform\":\"" + StringEscapeUtils.escapeJavaScript(
            descriptor.getPlatform()) + "\"");
        descriptorParts = sb.toString();
      }

      /* Look up bridge pool assignment. */
      if (this.bridgePoolAssignments.containsKey(fingerprint)) {
        bridgePoolAssignment = "\"pool_assignment\":\""
            + this.bridgePoolAssignments.get(fingerprint) + "\"";
      }

      /* Generate network-status-specific part. */
      Node entry = bridge.getValue();
      String running = entry.getRunning() ? "true" : "false";
      String address = entry.getAddress();
      int orPort = entry.getOrPort();
      StringBuilder sb = new StringBuilder();
      sb.append("{\"version\":1,\n"
          + "\"hashed_fingerprint\":\"" + fingerprint + "\",\n"
          + "\"or_addresses\":[\"" + address + ":" + orPort + "\"],\n"
          + "\"running\":" + running + ",");
      SortedSet<String> relayFlags = entry.getRelayFlags();
      if (!relayFlags.isEmpty()) {
        sb.append("\n\"flags\":[");
        int written = 0;
        for (String relayFlag : relayFlags) {
          sb.append((written++ > 0 ? "," : "") + "\"" + relayFlag + "\"");
        }
        sb.append("]");
      }

      /* Append descriptor and bridge pool assignment parts. */
      if (descriptorParts != null) {
        sb.append(",\n" + descriptorParts);
      }
      if (bridgePoolAssignment != null) {
        sb.append(",\n" + bridgePoolAssignment);
      }
      sb.append("\n}\n");
      String detailsLines = sb.toString();

      /* Write details file to disk. */
      File detailsFile = new File(detailsFileDirectory, fingerprint);
      try {
        detailsFile.getParentFile().mkdirs();
        BufferedWriter bw = new BufferedWriter(new FileWriter(
            detailsFile));
        bw.write(detailsLines);
        bw.close();
      } catch (IOException e) {
        System.err.println("Could not write details file '"
            + detailsFile.getAbsolutePath() + "'.  This file may now be "
            + "broken.  Ignoring.");
        e.printStackTrace();
      }
    }

    /* Return the files that we didn't update. */
    return result;
  }

  private void deleteDetailsFiles(
      SortedMap<String, File> remainingDetailsFiles) {
    for (File detailsFile : remainingDetailsFiles.values()) {
      detailsFile.delete();
    }
  }
}

