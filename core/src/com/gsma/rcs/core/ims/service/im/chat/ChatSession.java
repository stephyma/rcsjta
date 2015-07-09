/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
 * Copyright (C) 2014 Sony Mobile Communications Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NOTE: This file has been modified by Sony Mobile Communications Inc.
 * Modifications are licensed under the License.
 ******************************************************************************/

package com.gsma.rcs.core.ims.service.im.chat;

import static com.gsma.rcs.utils.StringUtils.UTF8;

import com.gsma.rcs.core.content.MmContent;
import com.gsma.rcs.core.ims.network.sip.FeatureTags;
import com.gsma.rcs.core.ims.network.sip.SipUtils;
import com.gsma.rcs.core.ims.protocol.msrp.MsrpEventListener;
import com.gsma.rcs.core.ims.protocol.msrp.MsrpException;
import com.gsma.rcs.core.ims.protocol.msrp.MsrpManager;
import com.gsma.rcs.core.ims.protocol.msrp.MsrpSession;
import com.gsma.rcs.core.ims.protocol.msrp.MsrpSession.TypeMsrpChunk;
import com.gsma.rcs.core.ims.protocol.sip.SipException;
import com.gsma.rcs.core.ims.protocol.sip.SipNetworkException;
import com.gsma.rcs.core.ims.protocol.sip.SipPayloadException;
import com.gsma.rcs.core.ims.protocol.sip.SipResponse;
import com.gsma.rcs.core.ims.service.ImsServiceError;
import com.gsma.rcs.core.ims.service.ImsServiceSession;
import com.gsma.rcs.core.ims.service.ImsSessionListener;
import com.gsma.rcs.core.ims.service.SessionActivityManager;
import com.gsma.rcs.core.ims.service.im.InstantMessagingService;
import com.gsma.rcs.core.ims.service.im.chat.cpim.CpimMessage;
import com.gsma.rcs.core.ims.service.im.chat.cpim.CpimParser;
import com.gsma.rcs.core.ims.service.im.chat.geoloc.GeolocInfoDocument;
import com.gsma.rcs.core.ims.service.im.chat.imdn.ImdnDocument;
import com.gsma.rcs.core.ims.service.im.chat.imdn.ImdnManager;
import com.gsma.rcs.core.ims.service.im.chat.imdn.ImdnUtils;
import com.gsma.rcs.core.ims.service.im.chat.iscomposing.IsComposingManager;
import com.gsma.rcs.core.ims.service.im.chat.standfw.TerminatingStoreAndForwardOneToOneChatMessageSession;
import com.gsma.rcs.core.ims.service.im.chat.standfw.TerminatingStoreAndForwardOneToOneChatNotificationSession;
import com.gsma.rcs.core.ims.service.im.filetransfer.FileSharingError;
import com.gsma.rcs.core.ims.service.im.filetransfer.FileSharingSession;
import com.gsma.rcs.core.ims.service.im.filetransfer.FileTransferUtils;
import com.gsma.rcs.core.ims.service.im.filetransfer.http.DownloadFromInviteFileSharingSession;
import com.gsma.rcs.core.ims.service.im.filetransfer.http.FileTransferHttpInfoDocument;
import com.gsma.rcs.core.ims.service.im.filetransfer.http.FileTransferHttpThumbnail;
import com.gsma.rcs.provider.contact.ContactManager;
import com.gsma.rcs.provider.messaging.MessagingLog;
import com.gsma.rcs.provider.settings.RcsSettings;
import com.gsma.rcs.utils.IdGenerator;
import com.gsma.rcs.utils.NetworkRessourceManager;
import com.gsma.rcs.utils.logger.Logger;
import com.gsma.services.rcs.chat.ChatLog.Message.MimeType;
import com.gsma.services.rcs.contact.ContactId;
import com.gsma.services.rcs.filetransfer.FileTransfer;
import com.gsma.services.rcs.filetransfer.FileTransfer.ReasonCode;

import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax2.sip.message.Response;

/**
 * Chat session
 * 
 * @author jexa7410
 */
public abstract class ChatSession extends ImsServiceSession implements MsrpEventListener {
    /**
     * Subject
     */
    private String mSubject;

    /**
     * MSRP manager
     */
    private final MsrpManager mMsrpMgr;

    /**
     * Is composing manager
     */
    private final IsComposingManager mIsComposingMgr = new IsComposingManager(this);

    /**
     * Session activity manager
     */
    private final SessionActivityManager mActivityMgr;

    /**
     * Contribution ID
     */
    private String mContributionId;

    /**
     * Feature tags
     */
    private List<String> mFeatureTags = new ArrayList<String>();

    /**
     * Feature tags
     */
    private List<String> mAcceptContactTags = new ArrayList<String>();

    /**
     * Accept types
     */
    private String mAcceptTypes;

    /**
     * Wrapped types
     */
    private String mWrappedTypes;

    /**
     * Geolocation push supported by remote
     */
    private boolean mGeolocSupportedByRemote = false;

    /**
     * File transfer supported by remote
     */
    private boolean mFtSupportedByRemote = false;

    private static final Logger sLogger = Logger.getLogger(ChatSession.class.getSimpleName());

    /**
     * Messaging log
     */
    protected final MessagingLog mMessagingLog;

    /**
     * First chat message
     */
    private final ChatMessage mFirstMsg;

    protected final InstantMessagingService mImService;

    protected final ImdnManager mImdnManager;

    /**
     * Receive chat message
     * 
     * @param msg Chat message
     * @param imdnDisplayedRequested Indicates whether display report was requested
     */
    protected void receive(ChatMessage msg, boolean imdnDisplayedRequested) {
        if (mMessagingLog.isMessagePersisted(msg.getMessageId())) {
            // Message already received
            return;
        }

        // Is composing event is reset
        mIsComposingMgr.receiveIsComposingEvent(msg.getRemoteContact(), false);

        for (ImsSessionListener listener : getListeners()) {
            ((ChatSessionListener) listener).handleReceiveMessage(msg, imdnDisplayedRequested);
        }
    }

    /**
     * Constructor
     * 
     * @param imService InstantMessagingService
     * @param contact Remote contactId
     * @param remoteUri Remote URI
     * @param rcsSettings RCS settings
     * @param messagingLog Messaging log
     * @param firstMsg First message in session
     * @param timestamp Local timestamp for the session
     * @param contactManager
     */
    public ChatSession(InstantMessagingService imService, ContactId contact, String remoteUri,
            RcsSettings rcsSettings, MessagingLog messagingLog, ChatMessage firstMsg,
            long timestamp, ContactManager contactManager) {
        super(imService, contact, remoteUri, rcsSettings, timestamp, contactManager);

        mImService = imService;
        mImdnManager = imService.getImdnManager();
        mMessagingLog = messagingLog;
        mActivityMgr = new SessionActivityManager(this, rcsSettings);

        // Create the MSRP manager
        int localMsrpPort = NetworkRessourceManager.generateLocalMsrpPort(rcsSettings);
        String localIpAddress = mImService.getImsModule().getCurrentNetworkInterface()
                .getNetworkAccess().getIpAddress();
        mMsrpMgr = new MsrpManager(localIpAddress, localMsrpPort, imService, rcsSettings);
        if (imService.getImsModule().isConnectedToWifiAccess()) {
            mMsrpMgr.setSecured(rcsSettings.isSecureMsrpOverWifi());
        }
        mFirstMsg = firstMsg;
    }

    /**
     * Get feature tags
     * 
     * @return Feature tags
     */
    public String[] getFeatureTags() {
        return mFeatureTags.toArray(new String[mFeatureTags.size()]);
    }

    /**
     * Get Accept-Contact tags
     * 
     * @return Feature tags
     */
    public String[] getAcceptContactTags() {
        return mAcceptContactTags.toArray(new String[mAcceptContactTags.size()]);
    }

    /**
     * Set feature tags
     * 
     * @param tags Feature tags
     */
    public void setFeatureTags(List<String> tags) {
        this.mFeatureTags = tags;
    }

    /**
     * Set Accept-Contact tags
     * 
     * @param tags Feature tags
     */
    public void setAcceptContactTags(List<String> tags) {
        this.mAcceptContactTags = tags;
    }

    /**
     * Get accept types
     * 
     * @return Accept types
     */
    public String getAcceptTypes() {
        return mAcceptTypes;
    }

    /**
     * Set accept types
     * 
     * @param types Accept types
     */
    public void setAcceptTypes(String types) {
        this.mAcceptTypes = types;
    }

    /**
     * Get wrapped types
     * 
     * @return Wrapped types
     */
    public String getWrappedTypes() {
        return mWrappedTypes;
    }

    /**
     * Set wrapped types
     * 
     * @param types Wrapped types
     */
    public void setWrappedTypes(String types) {
        this.mWrappedTypes = types;
    }

    /**
     * Returns the subject of the session
     * 
     * @return String
     */
    public String getSubject() {
        return mSubject;
    }

    /**
     * Set the subject of the session
     * 
     * @param subject Subject
     */
    public void setSubject(String subject) {
        mSubject = subject;
    }

    /**
     * Returns the session activity manager
     * 
     * @return Activity manager
     */
    public SessionActivityManager getActivityManager() {
        return mActivityMgr;
    }

    /**
     * Return the contribution ID
     * 
     * @return Contribution ID
     */
    public String getContributionID() {
        return mContributionId;
    }

    /**
     * Set the contribution ID
     * 
     * @param id Contribution ID
     */
    public void setContributionID(String id) {
        this.mContributionId = id;
    }

    /**
     * Returns the IM session identity
     * 
     * @return Identity (e.g. SIP-URI)
     */
    public String getImSessionIdentity() {
        if (getDialogPath() != null) {
            return getDialogPath().getTarget();
        } else {
            return null;
        }
    }

    /**
     * Returns the MSRP manager
     * 
     * @return MSRP manager
     */
    public MsrpManager getMsrpMgr() {
        return mMsrpMgr;
    }

    /**
     * Is geolocation supported by remote
     * 
     * @return Boolean
     */
    public boolean isGeolocSupportedByRemote() {
        return mGeolocSupportedByRemote;
    }

    /**
     * Set geolocation supported by remote
     * 
     * @param supported Supported
     */
    public void setGeolocSupportedByRemote(boolean supported) {
        this.mGeolocSupportedByRemote = supported;
    }

    /**
     * Is file transfer supported by remote
     * 
     * @return Boolean
     */
    public boolean isFileTransferSupportedByRemote() {
        return mFtSupportedByRemote;
    }

    /**
     * Set file transfer supported by remote
     * 
     * @param supported Supported
     */
    public void setFileTransferSupportedByRemote(boolean supported) {
        this.mFtSupportedByRemote = supported;
    }

    /**
     * Get first message.
     * 
     * @return first message
     */
    public ChatMessage getFirstMessage() {
        return mFirstMsg;
    }

    /**
     * Close the MSRP session
     */
    public void closeMsrpSession() {
        if (getMsrpMgr() != null) {
            getMsrpMgr().closeSession();
            if (sLogger.isActivated()) {
                sLogger.debug("MSRP session has been closed");
            }
        }
    }

    /**
     * Handle error
     * 
     * @param error Error
     */
    public void handleError(ImsServiceError error) {
        if (isSessionInterrupted()) {
            return;
        }

        // Error
        if (sLogger.isActivated()) {
            sLogger.info("Session error: " + error.getErrorCode() + ", reason="
                    + error.getMessage());
        }

        // Close media session
        closeMediaSession();

        // Remove the current session
        removeSession();

        for (int i = 0; i < getListeners().size(); i++) {
            ((ChatSessionListener) getListeners().get(i)).handleImError(new ChatError(error),
                    mFirstMsg);
        }
    }

    /**
     * Handle 480 Temporarily Unavailable
     * 
     * @param resp 480 response
     */
    public void handle480Unavailable(SipResponse resp) {
        handleError(new ChatError(ChatError.SESSION_INITIATION_DECLINED, resp.getReasonPhrase()));
    }

    /**
     * Handle 486 Busy
     * 
     * @param resp 486 response
     */
    public void handle486Busy(SipResponse resp) {
        handleError(new ChatError(ChatError.SESSION_INITIATION_DECLINED, resp.getReasonPhrase()));
    }

    /**
     * Handle 603 Decline
     * 
     * @param resp 603 response
     */
    public void handle603Declined(SipResponse resp) {
        handleDefaultError(resp);
    }

    /**
     * Data has been transfered
     * 
     * @param msgId Message ID
     */
    public void msrpDataTransfered(String msgId) {
        if (sLogger.isActivated()) {
            sLogger.info("Data transfered");
        }

        // Update the activity manager
        mActivityMgr.updateActivity();
    }

    /**
     * Session inactivity event
     */
    @Override
    public void handleInactivityEvent() {
        if (sLogger.isActivated()) {
            sLogger.debug("Session inactivity event");
        }

        terminateSession(TerminationReason.TERMINATION_BY_INACTIVITY);
    }

    /**
     * Data transfer has been received
     * 
     * @param msgId Message ID
     * @param data Received data
     * @param mimeType Data mime-type
     * @throws MsrpException
     * @throws SipPayloadException
     */
    public void msrpDataReceived(String msgId, byte[] data, String mimeType) throws MsrpException,
            SipPayloadException {
        if (sLogger.isActivated()) {
            sLogger.info("Data received (type " + mimeType + ")");
        }

        // Update the activity manager
        mActivityMgr.updateActivity();

        if ((data == null) || (data.length == 0)) {
            // By-pass empty data
            if (sLogger.isActivated()) {
                sLogger.debug("By-pass received empty data");
            }
            return;
        }

        if (ChatUtils.isApplicationIsComposingType(mimeType)) {
            // Is composing event
            receiveIsComposing(getRemoteContact(), data);
            return;
        }
        if (ChatUtils.isTextPlainType(mimeType)) {
            // Text message
            /**
             * Set message's timestamp to the System.currentTimeMillis, not the session's itself
             * timestamp
             */
            long timestamp = System.currentTimeMillis();
            /**
             * Since legacy server can send non CPIM data (like plain text without timestamp) in the
             * payload, we need to fake timesampSent by using the local timestamp even if this is
             * not the real proper timestamp from the remote side in this case.
             */
            ChatMessage msg = new ChatMessage(msgId, getRemoteContact(), new String(data, UTF8),
                    MimeType.TEXT_MESSAGE, timestamp, timestamp, null);
            boolean imdnDisplayedRequested = false;
            receive(msg, imdnDisplayedRequested);
            return;
        }
        if (ChatUtils.isMessageCpimType(mimeType)) {
            // Receive a CPIM message
            try {
                CpimParser cpimParser = new CpimParser(data);
                CpimMessage cpimMsg = cpimParser.getCpimMessage();
                if (cpimMsg != null) {
                    String cpimMsgId = cpimMsg.getHeader(ImdnUtils.HEADER_IMDN_MSG_ID);
                    if (cpimMsgId == null) {
                        cpimMsgId = msgId;
                    }

                    String contentType = cpimMsg.getContentType();
                    ContactId contact = getRemoteContact();
                    // In One to One chat, the MSRP 'from' header is
                    // '<sip:anonymous@anonymous.invalid>'

                    // Check if the message needs a delivery report
                    boolean imdnDisplayedRequested = false;

                    String dispositionNotification = cpimMsg
                            .getHeader(ImdnUtils.HEADER_IMDN_DISPO_NOTIF);
                    if (!isGroupChat()) {
                        // There is no display notification in Group Chat
                        if (dispositionNotification != null
                                && dispositionNotification.contains(ImdnDocument.DISPLAY)
                                && mImdnManager.isSendOneToOneDeliveryDisplayedReportsEnabled()) {
                            imdnDisplayedRequested = true;
                        }
                    }

                    boolean isFToHTTP = FileTransferUtils.isFileTransferHttpType(contentType);
                    /**
                     * Set message's timestamp to the System.currentTimeMillis, not the session's
                     * itself timestamp
                     */
                    long timestamp = System.currentTimeMillis();
                    long timestampSent = cpimMsg.getTimestampSent();

                    // Analyze received message thanks to the MIME type
                    if (isFToHTTP) {
                        // File transfer over HTTP message
                        // Parse HTTP document
                        FileTransferHttpInfoDocument fileInfo = FileTransferUtils
                                .parseFileTransferHttpDocument(cpimMsg.getMessageContent()
                                        .getBytes(UTF8), mRcsSettings);
                        if (fileInfo != null) {
                            receiveHttpFileTransfer(contact, getRemoteDisplayName(), fileInfo,
                                    cpimMsgId, timestamp, timestampSent);
                            // Mark the message as waiting a displayed report if needed
                            if (imdnDisplayedRequested) {
                                // TODO set File Transfer status to DISPLAY_REPORT_REQUESTED ??
                            }
                        } else {
                            // TODO : else return error to Originating side
                        }
                    } else {
                        if (ChatUtils.isTextPlainType(contentType)) {
                            ChatMessage msg = new ChatMessage(cpimMsgId, contact,
                                    cpimMsg.getMessageContent(), MimeType.TEXT_MESSAGE, timestamp,
                                    timestampSent, null);
                            receive(msg, imdnDisplayedRequested);
                        } else if (ChatUtils.isApplicationIsComposingType(contentType)) {
                            receiveIsComposing(contact, cpimMsg.getMessageContent().getBytes(UTF8));
                        } else if (ChatUtils.isMessageImdnType(contentType)) {
                            receiveDeliveryStatus(contact, cpimMsg.getMessageContent());
                        } else if (ChatUtils.isGeolocType(contentType)) {
                            ChatMessage msg = new ChatMessage(cpimMsgId, contact,
                                    cpimMsg.getMessageContent(), GeolocInfoDocument.MIME_TYPE,
                                    timestamp, timestampSent, null);
                            receive(msg, imdnDisplayedRequested);
                        }
                    }

                    if (!mImdnManager.isDeliveryDeliveredReportsEnabled()) {
                        return;
                    }

                    if (isFToHTTP) {
                        sendMsrpMessageDeliveryStatus(null, cpimMsgId,
                                ImdnDocument.DELIVERY_STATUS_DELIVERED, timestamp);
                    } else {
                        if (dispositionNotification != null) {
                            if (dispositionNotification.contains(ImdnDocument.POSITIVE_DELIVERY)) {
                                // Positive delivery requested, send MSRP message with status
                                // "delivered"
                                sendMsrpMessageDeliveryStatus(null, cpimMsgId,
                                        ImdnDocument.DELIVERY_STATUS_DELIVERED, timestamp);
                            }
                        }
                    }
                }
            } catch (SipNetworkException e) {
                throw new MsrpException(
                        "Unable to handle delivery status for msgId : ".concat(msgId), e);
            }
        } else {
            // Not supported content
            if (sLogger.isActivated()) {
                sLogger.debug("Not supported content " + mimeType + " in chat session");
            }
        }
    }

    /**
     * Data transfer in progress
     * 
     * @param currentSize Current transfered size in bytes
     * @param totalSize Total size in bytes
     */
    public void msrpTransferProgress(long currentSize, long totalSize) {
        // Not used by chat
    }

    /**
     * Data transfer in progress
     * 
     * @param currentSize Current transfered size in bytes
     * @param totalSize Total size in bytes
     * @param data received data chunk
     * @return always false TODO
     */
    public boolean msrpTransferProgress(long currentSize, long totalSize, byte[] data) {
        // Not used by chat
        return false;
    }

    /**
     * Data transfer has been aborted
     */
    public void msrpTransferAborted() {
        // Not used by chat
    }

    /**
     * Receive is composing event
     * 
     * @param contact Contact
     * @param event Event
     */
    protected void receiveIsComposing(ContactId contact, byte[] event) {
        mIsComposingMgr.receiveIsComposingEvent(contact, event);
    }

    /**
     * Send an empty data chunk
     * 
     * @throws MsrpException
     */
    public void sendEmptyDataChunk() throws MsrpException {
        mMsrpMgr.sendEmptyChunk();
    }

    private void handleFileTransferInvitationRejected(ContactId contact, MmContent content,
            MmContent fileIcon, FileTransfer.ReasonCode reasonCode, long timestamp,
            long timestampSent) {
        mImService
                .getImsModule()
                .getCoreListener()
                .handleFileTransferInvitationRejected(contact, content, fileIcon, reasonCode,
                        timestamp, timestampSent);
    }

    private void handleResendFileTransferInvitationRejected(String fileTransferId,
            ContactId contact, FileTransfer.ReasonCode reasonCode, long timestamp,
            long timestampSent) {
        mImService
                .getImsModule()
                .getCoreListener()
                .handleResendFileTransferInvitationRejected(fileTransferId, contact, reasonCode,
                        timestamp, timestampSent);
    }

    /**
     * Receive HTTP file transfer event
     * 
     * @param contact Contact
     * @param displayName Display Name
     * @param fileTransferInfo Information of on file to transfer over HTTP
     * @param msgId Message ID
     * @param timestamp The local timestamp of the message for receiving a HttpFileTransfer
     * @param timestampSent Remote timestamp sent in payload for receiving a HttpFileTransfer
     */
    protected void receiveHttpFileTransfer(ContactId contact, String displayName,
            FileTransferHttpInfoDocument fileTransferInfo, String msgId, long timestamp,
            long timestampSent) {
        FileTransferHttpThumbnail fileTransferHttpThumbnail = fileTransferInfo.getFileThumbnail();
        if (mImService.isFileTransferAlreadyOngoing(msgId)) {
            if (sLogger.isActivated()) {
                sLogger.debug(new StringBuilder("File transfer with fileTransferId '")
                        .append(msgId).append("' already ongoing, so ignoring this one.")
                        .toString());
            }
            return;
        }
        boolean fileResent = mImService.isFileTransferResentAndNotAlreadyOngoing(msgId);
        if (mContactManager.isBlockedForContact(contact)) {
            if (sLogger.isActivated()) {
                sLogger.debug(new StringBuilder("Contact ").append(contact)
                        .append(" is blocked, reject the HTTP File transfer").toString());
            }
            if (fileResent) {
                handleResendFileTransferInvitationRejected(msgId, contact,
                        ReasonCode.REJECTED_SPAM, timestamp, timestampSent);
                return;
            }
            MmContent fileIconContent = (fileTransferHttpThumbnail == null) ? null
                    : fileTransferHttpThumbnail.getLocalMmContent(msgId);
            handleFileTransferInvitationRejected(contact, fileTransferInfo.getLocalMmContent(),
                    fileIconContent, ReasonCode.REJECTED_SPAM, timestamp, timestampSent);
            return;
        }
        /* Auto reject if file too big or size exceeds device storage capacity. */
        FileSharingError error = FileSharingSession.isFileCapacityAcceptable(
                fileTransferInfo.getSize(), mRcsSettings);
        if (error != null) {
            if (sLogger.isActivated()) {
                sLogger.debug("File is too big or exceeds storage capacity, "
                        .concat("reject the HTTP File transfer."));
            }
            MmContent fileIconContent = (fileTransferHttpThumbnail == null) ? null
                    : fileTransferHttpThumbnail.getLocalMmContent(msgId);

            int errorCode = error.getErrorCode();
            switch (errorCode) {
                case FileSharingError.MEDIA_SIZE_TOO_BIG:
                    if (fileResent) {
                        handleResendFileTransferInvitationRejected(msgId, contact,
                                ReasonCode.REJECTED_MAX_SIZE, timestamp, timestampSent);
                        break;
                    }
                    handleFileTransferInvitationRejected(contact,
                            fileTransferInfo.getLocalMmContent(), fileIconContent,
                            ReasonCode.REJECTED_MAX_SIZE, timestamp, timestampSent);
                    break;
                case FileSharingError.NOT_ENOUGH_STORAGE_SPACE:
                    if (fileResent) {
                        handleResendFileTransferInvitationRejected(msgId, contact,
                                ReasonCode.REJECTED_LOW_SPACE, timestamp, timestampSent);
                        break;
                    }
                    handleFileTransferInvitationRejected(contact,
                            fileTransferInfo.getLocalMmContent(), fileIconContent,
                            ReasonCode.REJECTED_LOW_SPACE, timestamp, timestampSent);
                    break;
                default:
                    if (sLogger.isActivated()) {
                        sLogger.error("Unexpected error while receiving HTTP file transfer."
                                .concat(Integer.toString(errorCode)));
                    }
            }
            return;
        }

        // Auto reject if number max of FT reached
        if (!mImService.getImsModule().getInstantMessagingService()
                .isFileTransferSessionAvailable()) {
            if (sLogger.isActivated()) {
                sLogger.debug("Max number of File Tranfer reached, reject the HTTP File transfer");
            }
            MmContent fileIconContent = (fileTransferHttpThumbnail == null) ? null
                    : fileTransferHttpThumbnail.getLocalMmContent(msgId);
            if (fileResent) {
                handleResendFileTransferInvitationRejected(msgId, contact,
                        ReasonCode.REJECTED_MAX_FILE_TRANSFERS, timestamp, timestampSent);
                return;
            }
            handleFileTransferInvitationRejected(contact, fileTransferInfo.getLocalMmContent(),
                    fileIconContent, ReasonCode.REJECTED_MAX_FILE_TRANSFERS, timestamp,
                    timestampSent);
            return;
        }

        DownloadFromInviteFileSharingSession fileSession = new DownloadFromInviteFileSharingSession(
                mImService, this, fileTransferInfo, msgId, contact, displayName, mRcsSettings,
                mMessagingLog, timestamp, timestampSent, mContactManager);
        if (fileTransferHttpThumbnail != null) {
            try {
                fileSession.downloadFileIcon();
            } catch (IOException e) {
                if (sLogger.isActivated()) {
                    sLogger.error("Failed to download file icon", e);
                }
                MmContent fileIconContent = fileTransferHttpThumbnail.getLocalMmContent(msgId);
                if (fileResent) {
                    handleResendFileTransferInvitationRejected(msgId, contact,
                            ReasonCode.REJECTED_MAX_FILE_TRANSFERS, timestamp, timestampSent);
                    return;
                }
                handleFileTransferInvitationRejected(contact, fileTransferInfo.getLocalMmContent(),
                        fileIconContent, ReasonCode.REJECTED_MEDIA_FAILED, timestamp, timestampSent);
                return;
            }
        }
        if (fileResent) {
            mImService.getImsModule().getCoreListener()
                    .handleOneToOneResendFileTransferInvitation(fileSession, contact, displayName);
        } else {
            mImService
                    .getImsModule()
                    .getCoreListener()
                    .handleFileTransferInvitation(fileSession, isGroupChat(), contact, displayName,
                            fileTransferInfo.getExpiration());
        }
        fileSession.startSession();
    }

    /**
     * Send data chunk with a specified MIME type
     * 
     * @param msgId Message ID
     * @param data Data
     * @param mime MIME type
     * @param typeMsrpChunk Type of MSRP chunk
     * @throws MsrpException
     */
    public void sendDataChunks(String msgId, String data, String mime, TypeMsrpChunk typeMsrpChunk)
            throws MsrpException {
        byte[] bytes = data.getBytes(UTF8);
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
        mMsrpMgr.sendChunks(stream, msgId, mime, bytes.length, typeMsrpChunk);

    }

    /**
     * Is group chat
     * 
     * @return Boolean
     */
    public abstract boolean isGroupChat();

    /**
     * Is Store & Forward
     * 
     * @return Boolean
     */
    public boolean isStoreAndForward() {
        if (this instanceof TerminatingStoreAndForwardOneToOneChatMessageSession
                || this instanceof TerminatingStoreAndForwardOneToOneChatNotificationSession) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Send a chat message
     * 
     * @param msg Chat message
     * @throws MsrpException
     */
    public abstract void sendChatMessage(ChatMessage msg) throws MsrpException;

    /**
     * Send is composing status
     * 
     * @param status Status
     * @throws MsrpException
     */
    public abstract void sendIsComposingStatus(boolean status) throws MsrpException;

    /**
     * Send message delivery status via MSRP
     * 
     * @param remote Contact that requested the delivery status
     * @param msgId Message ID
     * @param status Status
     * @param timestamp Timestamp sent in payload for IMDN datetime
     * @throws MsrpException
     */
    public void sendMsrpMessageDeliveryStatus(ContactId remote, String msgId, String status,
            long timestamp) throws MsrpException {
        // Send status in CPIM + IMDN headers
        String from = ChatUtils.ANONYMOUS_URI;
        String to = ChatUtils.ANONYMOUS_URI;
        sendMsrpMessageDeliveryStatus(from, to, msgId, status, timestamp);
    }

    /**
     * Send message delivery status via MSRP
     * 
     * @param fromUri Uri from who will send the delivery status
     * @param toUri Uri from who requested the delivery status
     * @param msgId Message ID
     * @param status Status
     * @param timestamp Timestamp sent in payload for IMDN datetime
     * @throws MsrpException
     */
    public void sendMsrpMessageDeliveryStatus(String fromUri, String toUri, String msgId,
            String status, long timestamp) throws MsrpException {

        if (sLogger.isActivated()) {
            sLogger.debug("Send delivery status " + status + " for message " + msgId);
        }
        // Send status in CPIM + IMDN headers
        /* Timestamp fo IMDN datetime */
        String imdn = ChatUtils.buildImdnDeliveryReport(msgId, status, timestamp);
        /* Timestamp for CPIM DateTime */
        String content = ChatUtils.buildCpimDeliveryReport(fromUri, toUri, imdn,
                System.currentTimeMillis());

        TypeMsrpChunk typeMsrpChunk = TypeMsrpChunk.OtherMessageDeliveredReportStatus;
        if (status.equalsIgnoreCase(ImdnDocument.DELIVERY_STATUS_DISPLAYED)) {
            typeMsrpChunk = TypeMsrpChunk.MessageDisplayedReport;
        } else {
            if (status.equalsIgnoreCase(ImdnDocument.DELIVERY_STATUS_DELIVERED)) {
                typeMsrpChunk = TypeMsrpChunk.MessageDeliveredReport;
            }
        }

        // Send data
        sendDataChunks(IdGenerator.generateMessageID(), content, CpimMessage.MIME_TYPE,
                typeMsrpChunk);
        if (ImdnDocument.DELIVERY_STATUS_DISPLAYED.equals(status)) {
            if (mMessagingLog.getMessageChatId(msgId) != null) {
                for (ImsSessionListener listener : getListeners()) {
                    ((ChatSessionListener) listener).handleChatMessageDisplayReportSent(msgId);
                }
            }
        }
    }

    /**
     * Receive a message delivery status from an XML document
     * 
     * @param contact Contact identifier
     * @param xml XML document
     * @throws SipPayloadException
     * @throws SipNetworkException
     */
    public void receiveDeliveryStatus(ContactId contact, String xml) throws SipPayloadException,
            SipNetworkException {
        try {
            ImdnDocument imdn = ChatUtils.parseDeliveryReport(xml);

            for (ImsSessionListener listener : getListeners()) {
                ((ChatSessionListener) listener).handleDeliveryStatus(mContributionId, contact,
                        imdn);
            }
        } catch (SAXException e) {
            throw new SipPayloadException(new StringBuilder(
                    "Failed to parse IMDN document for contact : ").append(contact).toString(), e);

        } catch (ParserConfigurationException e) {
            throw new SipPayloadException(new StringBuilder(
                    "Failed to parse IMDN document for contact : ").append(contact).toString(), e);

        } catch (IOException e) {
            throw new SipNetworkException(new StringBuilder(
                    "Failed to parse IMDN document for contact : ").append(contact).toString(), e);
        }
    }

    /**
     * Prepare media session
     * 
     * @throws MsrpException
     */
    public void prepareMediaSession() throws MsrpException {
        // Changed by Deutsche Telekom
        // Get the remote SDP part
        byte[] sdp = getDialogPath().getRemoteContent().getBytes(UTF8);

        // Changed by Deutsche Telekom
        // Create the MSRP session
        MsrpSession session = getMsrpMgr().createMsrpSession(sdp, this);

        session.setFailureReportOption(false);
        session.setSuccessReportOption(false);
    }

    /**
     * Open media session
     * 
     * @throws IOException
     */
    public void openMediaSession() throws IOException {
        getMsrpMgr().openMsrpSession();
        sendEmptyDataChunk();
    }

    /**
     * Start media transfer
     * 
     * @throws IOException
     */
    public void startMediaTransfer() throws IOException {
        /* Not used here */
    }

    /**
     * Reject the session invitation
     */
    public abstract void rejectSession();

    /**
     * Handle 200 0K response
     * 
     * @param resp 200 OK response
     * @throws SipException
     */
    public void handle200OK(SipResponse resp) throws SipException {
        super.handle200OK(resp);

        // Check if geolocation push supported by remote
        setGeolocSupportedByRemote(SipUtils.isFeatureTagPresent(resp,
                FeatureTags.FEATURE_RCSE_GEOLOCATION_PUSH));

        // Check if file transfer supported by remote
        setFileTransferSupportedByRemote(SipUtils.isFeatureTagPresent(resp,
                FeatureTags.FEATURE_RCSE_FT)
                || SipUtils.isFeatureTagPresent(resp, FeatureTags.FEATURE_RCSE_FT_HTTP));
    }

    /**
     * Is media session established
     * 
     * @return true If the empty packet was sent successfully
     */
    public boolean isMediaEstablished() {
        return (getMsrpMgr().isEstablished() && !getDialogPath().isSessionTerminated());
    }
}
