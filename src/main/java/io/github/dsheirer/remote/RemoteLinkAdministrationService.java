/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import java.util.List;

/**
 * Administration boundary for authenticated remote senders and their advertised P25 feeds.
 *
 * <p>Methods are called on web request threads. Implementations must return immutable snapshots and must not wait on
 * decode callbacks or perform work on real-time receiver threads. Credentials are exposed only by
 * {@link #createSender(long, CreateSenderRequest)} and are never included in later snapshots.</p>
 */
public interface RemoteLinkAdministrationService extends RemoteOriginLookup
{
    RemoteLinkSnapshot snapshot();

    RemoteLinkSnapshot updateListener(long expectedRevision, ListenerUpdate request);

    RemoteLinkSnapshot updateSenderConnection(long expectedRevision, SenderConnectionUpdate request);

    RemoteLinkSnapshot removeSenderConnection(long expectedRevision);

    default RemoteLinkSnapshot createSenderConnection(long expectedRevision, SenderConnectionUpdate request)
    {
        throw new UnsupportedOperationException();
    }

    default RemoteLinkSnapshot updateSenderConnection(long expectedRevision, String destinationId,
                                                       SenderConnectionUpdate request)
    {
        throw new UnsupportedOperationException();
    }

    default RemoteLinkSnapshot removeSenderConnection(long expectedRevision, String destinationId)
    {
        throw new UnsupportedOperationException();
    }

    CreateSenderResult createSender(long expectedRevision, CreateSenderRequest request);

    RemoteLinkSnapshot updateSender(long expectedRevision, String senderId, UpdateSenderRequest request);

    RemoteLinkSnapshot removeSender(long expectedRevision, String senderId);

    RemoteLinkSnapshot updateFeed(long expectedRevision, String senderId, String feedId, UpdateFeedRequest request);

    enum ListenerState
    {
        STOPPED,
        STARTING,
        LISTENING,
        ERROR
    }

    enum DependencyState
    {
        READY,
        DEGRADED,
        MISSING,
        UNKNOWN
    }

    enum SenderState
    {
        WAITING,
        CONNECTED,
        DISCONNECTED,
        REVOKED,
        ERROR
    }

    enum SenderConnectionState
    {
        DISABLED,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        AUTHENTICATION_FAILED,
        ERROR
    }

    enum FeedState
    {
        PENDING,
        CONNECTED,
        DISCONNECTED,
        REMOVED,
        UNSUPPORTED
    }

    record RemoteLinkSnapshot(long revision, ListenerSnapshot listener,
                              List<SenderConnectionSnapshot> senderConnections,
                              List<ExportChannelOption> exportChannelOptions,
                              List<AliasListOption> aliasLists, List<SenderSnapshot> senders, boolean restartRequired)
    {
        public RemoteLinkSnapshot
        {
            if(revision < 1L)
            {
                throw new IllegalArgumentException("Remote-link revision must be positive");
            }

            listener = listener != null ? listener : ListenerSnapshot.STOPPED;
            senderConnections = senderConnections != null ? List.copyOf(senderConnections) : List.of();
            exportChannelOptions = exportChannelOptions != null ? List.copyOf(exportChannelOptions) : List.of();
            aliasLists = aliasLists != null ? List.copyOf(aliasLists) : List.of();
            senders = senders != null ? List.copyOf(senders) : List.of();
        }

        public RemoteLinkSnapshot(long revision, ListenerSnapshot listener, SenderConnectionSnapshot senderConnection,
                                  List<ExportChannelOption> exportChannelOptions, List<AliasListOption> aliasLists,
                                  List<SenderSnapshot> senders, boolean restartRequired)
        {
            this(revision, listener, senderConnection != null && senderConnection != SenderConnectionSnapshot.DISABLED ?
                List.of(senderConnection) : List.of(), exportChannelOptions, aliasLists, senders, restartRequired);
        }

        /** Compatibility view for clients configured before multiple destinations were supported. */
        public SenderConnectionSnapshot senderConnection()
        {
            return senderConnections.isEmpty() ? SenderConnectionSnapshot.DISABLED : senderConnections.getFirst();
        }
    }

    record ListenerSnapshot(boolean enabled, String bindAddress, int port, ListenerState state,
                            String statusMessage, List<DependencySnapshot> dependencies)
    {
        public static final ListenerSnapshot STOPPED = new ListenerSnapshot(false,
            RemoteLinkSettingsStore.DEFAULT_BIND_ADDRESS, 0,
            ListenerState.STOPPED, null, List.of());

        public ListenerSnapshot
        {
            state = state != null ? state : ListenerState.STOPPED;
            dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        }
    }

    record DependencySnapshot(String id, String label, DependencyState state, String statusMessage)
    {
        public DependencySnapshot
        {
            state = state != null ? state : DependencyState.UNKNOWN;
        }
    }

    record AliasListOption(long aliasListId, String name)
    {
    }

    /** One independently managed outbound destination. The shared secret is deliberately absent. */
    record SenderConnectionSnapshot(String destinationId, String displayName, boolean enabled, String destinationHost, int destinationPort, String senderId,
                                    boolean credentialConfigured, SenderConnectionState state,
                                    long lastConnectedAtMs, String statusMessage,
                                    List<String> exportedChannelConfigurationIds)
    {
        public static final SenderConnectionSnapshot DISABLED = new SenderConnectionSnapshot(null, null, false, null, 0, null,
            false, SenderConnectionState.DISABLED, 0L, null, List.of());

        public SenderConnectionSnapshot(boolean enabled, String destinationHost, int destinationPort, String senderId,
                                        boolean credentialConfigured, SenderConnectionState state,
                                        long lastConnectedAtMs, String statusMessage,
                                        List<String> exportedChannelConfigurationIds)
        {
            this("00000000-0000-4000-8000-000000000001", "Destination", enabled, destinationHost, destinationPort,
                senderId, credentialConfigured, state, lastConnectedAtMs, statusMessage, exportedChannelConfigurationIds);
        }

        public SenderConnectionSnapshot
        {
            state = state != null ? state : SenderConnectionState.DISCONNECTED;
            exportedChannelConfigurationIds = exportedChannelConfigurationIds != null ?
                List.copyOf(exportedChannelConfigurationIds) : List.of();
        }
    }

    /** Saved P25 trunked channel eligible for export to the configured destination. */
    record ExportChannelOption(String channelConfigurationId, String name, String systemName, String siteName,
                               String protocol)
    {
    }

    record SenderSnapshot(String senderId, String displayName, SenderState state, boolean credentialConfigured,
                          long pairedAtMs, long lastSeenAtMs, Long defaultAliasListId,
                          String statusMessage, List<FeedSnapshot> feeds)
    {
        public SenderSnapshot
        {
            state = state != null ? state : SenderState.DISCONNECTED;
            feeds = feeds != null ? List.copyOf(feeds) : List.of();
        }
    }

    record FeedSnapshot(String feedId, String advertisedName, String displayName, String protocol,
                        String systemName, String siteName, Integer wacn, Integer system, Integer rfss, Integer site,
                        long frequencyHz,
                        FeedState state, boolean enabled, String channelConfigurationId,
                        Long aliasListId, long lastSeenAtMs, Long lagMilliseconds, long droppedPacketCount,
                        long sequenceGapCount, String statusMessage)
    {
        public FeedSnapshot
        {
            state = state != null ? state : FeedState.DISCONNECTED;
            frequencyHz = Math.max(0L, frequencyHz);
            droppedPacketCount = Math.max(0L, droppedPacketCount);
            sequenceGapCount = Math.max(0L, sequenceGapCount);
        }
    }

    record ListenerUpdate(boolean enabled, String bindAddress, int port)
    {
    }

    /** A null or blank secret keeps the configured credential when one already exists. */
    record SenderConnectionUpdate(String displayName, boolean enabled, String destinationHost, int destinationPort, String senderId,
                                  String secret, List<String> exportedChannelConfigurationIds)
    {
        public SenderConnectionUpdate(boolean enabled, String destinationHost, int destinationPort, String senderId,
                                      String secret, List<String> exportedChannelConfigurationIds)
        {
            this(null, enabled, destinationHost, destinationPort, senderId, secret, exportedChannelConfigurationIds);
        }

        public SenderConnectionUpdate
        {
            exportedChannelConfigurationIds = exportedChannelConfigurationIds != null ?
                List.copyOf(exportedChannelConfigurationIds) : List.of();
        }

        @Override
        public String toString()
        {
            return "SenderConnectionUpdate[enabled=" + enabled + ", destinationHost=" + destinationHost +
                ", destinationPort=" + destinationPort + ", senderId=" + senderId + ", secret=<redacted>" +
                ", exportedChannelConfigurationIds=" + exportedChannelConfigurationIds + "]";
        }
    }

    record CreateSenderRequest(String displayName)
    {
    }

    /** The secret is returned once and must not be retained by the web layer after the response is written. */
    record CreateSenderResult(long revision, String senderId, String displayName, String secret)
    {
        @Override
        public String toString()
        {
            return "CreateSenderResult[revision=" + revision + ", senderId=" + senderId + ", displayName=" +
                displayName + ", secret=<redacted>]";
        }
    }

    record UpdateSenderRequest(String displayName, Long defaultAliasListId)
    {
    }

    record UpdateFeedRequest(String displayName, long aliasListId, boolean enabled)
    {
    }

    class NotFoundException extends RuntimeException
    {
        public NotFoundException()
        {
        }
    }

    class StaleRevisionException extends RuntimeException
    {
        public StaleRevisionException()
        {
        }
    }

    class ConflictException extends RuntimeException
    {
        public ConflictException()
        {
        }
    }

    class BusyException extends RuntimeException
    {
        public BusyException()
        {
        }
    }

    class UnavailableException extends RuntimeException
    {
        public UnavailableException()
        {
        }
    }
}
