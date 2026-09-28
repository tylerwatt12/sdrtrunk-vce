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

    CreateSenderResult createSender(long expectedRevision, CreateSenderRequest request);

    RemoteLinkSnapshot updateSender(long expectedRevision, String senderId, UpdateSenderRequest request);

    RemoteLinkSnapshot revokeSender(long expectedRevision, String senderId);

    RemoteLinkSnapshot adoptFeed(long expectedRevision, String senderId, String feedId, AdoptFeedRequest request);

    RemoteLinkSnapshot updateFeed(long expectedRevision, String senderId, String feedId, UpdateFeedRequest request);

    RemoteLinkSnapshot forgetFeed(long expectedRevision, String senderId, String feedId);

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
                              SenderConnectionSnapshot senderConnection,
                              List<ExportChannelOption> exportChannelOptions,
                              List<AliasListOption> aliasLists, List<SenderSnapshot> senders)
    {
        public RemoteLinkSnapshot
        {
            if(revision < 1L)
            {
                throw new IllegalArgumentException("Remote-link revision must be positive");
            }

            listener = listener != null ? listener : ListenerSnapshot.STOPPED;
            senderConnection = senderConnection != null ? senderConnection : SenderConnectionSnapshot.DISABLED;
            exportChannelOptions = exportChannelOptions != null ? List.copyOf(exportChannelOptions) : List.of();
            aliasLists = aliasLists != null ? List.copyOf(aliasLists) : List.of();
            senders = senders != null ? List.copyOf(senders) : List.of();
        }
    }

    record ListenerSnapshot(boolean enabled, String bindAddress, int port, ListenerState state,
                            String statusMessage, List<DependencySnapshot> dependencies)
    {
        public static final ListenerSnapshot STOPPED = new ListenerSnapshot(false, "127.0.0.1", 0,
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

    /** This installation's one outbound destination. The shared secret is deliberately absent. */
    record SenderConnectionSnapshot(boolean enabled, String destinationHost, int destinationPort, String senderId,
                                    boolean credentialConfigured, SenderConnectionState state,
                                    long lastConnectedAtMs, String statusMessage,
                                    List<String> exportedChannelConfigurationIds)
    {
        public static final SenderConnectionSnapshot DISABLED = new SenderConnectionSnapshot(false, null, 0, null,
            false, SenderConnectionState.DISABLED, 0L, null, List.of());

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
                          long pairedAtMs, long lastSeenAtMs, boolean autoAdopt, Long defaultAliasListId,
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
                        FeedState state, boolean adopted, boolean enabled, String channelConfigurationId,
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
    record SenderConnectionUpdate(boolean enabled, String destinationHost, int destinationPort, String senderId,
                                  String secret, List<String> exportedChannelConfigurationIds)
    {
        public SenderConnectionUpdate
        {
            exportedChannelConfigurationIds = exportedChannelConfigurationIds != null ?
                List.copyOf(exportedChannelConfigurationIds) : List.of();
        }
    }

    record CreateSenderRequest(String displayName)
    {
    }

    /** The secret is returned once and must not be retained by the web layer after the response is written. */
    record CreateSenderResult(long revision, String senderId, String displayName, String secret)
    {
    }

    record UpdateSenderRequest(String displayName, boolean autoAdopt, Long defaultAliasListId)
    {
    }

    record AdoptFeedRequest(String displayName, long aliasListId)
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
