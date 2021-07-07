/**
 *
 * Copyright 2015-2020 Florian Schmaus, 2021 Dan Caseley
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.smackx.muc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.PresenceListener;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.sm.predicates.ForEveryMessage;

import org.jivesoftware.smackx.muc.packet.MUCUser;

import org.igniterealtime.smack.inttest.SmackIntegrationTestEnvironment;
import org.igniterealtime.smack.inttest.TestNotPossibleException;
import org.igniterealtime.smack.inttest.annotations.SmackIntegrationTest;
import org.igniterealtime.smack.inttest.util.MultiResultSyncPoint;
import org.igniterealtime.smack.inttest.util.ResultSyncPoint;
import org.igniterealtime.smack.inttest.util.SimpleResultSyncPoint;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;

public class MultiUserChatOccupantIntegrationTest extends AbstractMultiUserChatIntegrationTest {

    public MultiUserChatOccupantIntegrationTest(SmackIntegrationTestEnvironment environment)
                    throws SmackException.NoResponseException, XMPPException.XMPPErrorException,
                    SmackException.NotConnectedException, InterruptedException, TestNotPossibleException {
        super(environment);
    }

    /**
     * Asserts that when a user joins a room, all events are received, and in the correct order.
     *
     * <p>From XEP-0045 § 7.1:</p>
     * <blockquote>
     * The order of events involved in joining a room needs to be consistent so that clients can know which events to
     * expect when. After a client sends presence to join a room, the MUC service MUST send it events in the following
     * order:
     *   1. In-room presence from other occupants
     *   2. In-room presence from the joining entity itself (so-called "self-presence")
     *   3. Room history (if any)
     *   4. The room subject
     *   ...
     * </blockquote>
     *
     * <p>From XEP-0045 § 7.2.2</p>
     * <blockquote>
     * This self-presence MUST NOT be sent to the new occupant until the room has sent the presence of all other
     * occupants to the new occupant ... The service MUST first send the complete list of the existing occupants
     * to the new occupant and only then send the new occupant's own presence to the new occupant
     * </blockquote>
     *
     * @throws Exception when errors occur
     */
    @SmackIntegrationTest
    public void mucJoinEventOrderingTest() throws Exception {
        EntityBareJid mucAddress = getRandomRoom("smack-inttest-eventordering");
        final String mucSubject = "Subject smack-inttest-eventordering " + randomString;
        final String mucMessage = "Message smack-inttest-eventordering " + randomString;


        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);

        final Resourcepart nicknameOne = Resourcepart.from("one-" + randomString);
        final Resourcepart nicknameTwo = Resourcepart.from("two-" + randomString);

        createMuc(mucAsSeenByOne, nicknameOne);
        mucAsSeenByOne.changeSubject(mucSubject); // Blocks until confirmed.

        // Send and wait for the message to have been reflected, so that we can be sure it's part of the MUC history.
        final SimpleResultSyncPoint messageReflectionSyncPoint = new SimpleResultSyncPoint();
        mucAsSeenByOne.addMessageListener( message -> {
            if (message.getBody().equals(mucMessage)) {
                messageReflectionSyncPoint.signal();
            }
        });

        mucAsSeenByOne.sendMessage(mucMessage);
        messageReflectionSyncPoint.waitForResult(timeout);

        final ResultSyncPoint<String, Exception> subjectResultSyncPoint = new ResultSyncPoint<>();
        List<Object> results = new ArrayList<Object>();

        mucAsSeenByTwo.addMessageListener(new MessageListener() {
            @Override
            public void processMessage(Message message) {
                String body = message.getBody();
                if (mucMessage.equals(body)) {
                    results.add(body);
                }
            }
        });

        mucAsSeenByTwo.addParticipantStatusListener(new ParticipantStatusListener() {
            @Override
            public void joined(EntityFullJid participant) {
                results.add(participant);
            }
        });

        mucAsSeenByTwo.addSubjectUpdatedListener(new SubjectUpdatedListener() {
            @Override
            public void subjectUpdated(String subject, EntityFullJid from) {
                results.add(subject);
                subjectResultSyncPoint.signal(subject);
            }
        });

        try {
            Presence reflectedJoinPresence = mucAsSeenByTwo.join(nicknameTwo);
            results.add(reflectedJoinPresence.getFrom()); // Self-presence should be second

            subjectResultSyncPoint.waitForResult(timeout); // Wait for subject, as it should be 4th (last)

            assertEquals(4, results.size());
            assertEquals(JidCreate.fullFrom(mucAddress, nicknameOne), results.get(0));
            assertEquals(JidCreate.fullFrom(mucAddress, nicknameTwo), results.get(1));
            assertEquals(mucMessage, results.get(2));
            assertEquals(mucSubject, results.get(3));
        } finally {
            tryDestroy(mucAsSeenByOne);
        }
    }

    /**
     * Asserts that when a user sends a message to a room without joining, they receive an error and the message is not
     * sent to the occupants.
     *
     * <p>From XEP-0045 § 7.2.1:</p>
     * <blockquote>
     * In order to participate in the discussions held in a multi-user chat room, a user MUST first become an occupant
     * by entering the room
     * </blockquote>
     *
     * <p>From XEP-0045 § 7.4:</p>
     * <blockquote>
     * If the sender is not an occupant of the room, the service SHOULD return a &lt;not-acceptable/&gt; error to the
     * sender and SHOULD NOT reflect the message to all occupants
     * </blockquote>
     *
     * @throws Exception when errors occur
     */
    @SmackIntegrationTest
    public void mucSendBeforeJoiningTest() throws Exception {
        EntityBareJid mucAddress = getRandomRoom("smack-inttest-send-without-joining");

        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);

        createMuc(mucAsSeenByOne, Resourcepart.from("one-" + randomString));

        ResultSyncPoint<Message, Exception> errorMessageResultSyncPoint = new ResultSyncPoint<>();
        conTwo.addStanzaListener(new StanzaListener() {
            @Override public void processStanza(Stanza packet)
                            throws SmackException.NotConnectedException, InterruptedException,
                            SmackException.NotLoggedInException {
                errorMessageResultSyncPoint.signal((Message) packet);
            }
        }, ForEveryMessage.INSTANCE);

        ResultSyncPoint<Message, Exception> distributedMessageResultSyncPoint = new ResultSyncPoint<>();
        mucAsSeenByOne.addMessageListener(new MessageListener() {
            @Override public void processMessage(Message message) {
                distributedMessageResultSyncPoint.signal(message);
            }
        });

        try {
            mucAsSeenByTwo.sendMessage("Message without Joining");
            Message response = errorMessageResultSyncPoint.waitForResult(timeout);
            assertEquals("not-acceptable", response.getError().getCondition().toString());
            assertThrows(TimeoutException.class, () -> distributedMessageResultSyncPoint.waitForResult(1000));
        } finally {
            tryDestroy(mucAsSeenByOne);
        }
    }

    /**
     * Asserts that when a user joins a room, they are sent presence information about existing participants and
     * themselves that includes role and affiliation information and appropriate status codes
     *
     * <p>From XEP-0045 § 7.2.2:</p>
     * <blockquote>
     * If the service is able to add the user to the room, it MUST send presence from all the existing
     * participants' occupant JIDs to the new occupant's full JID, including extended presence information about roles
     * in a single &lt;x/&gt; element qualified by the 'http://jabber.org/protocol/muc#user' namespace and containing an
     * &lt;item/&gt; child with the 'role' attribute set to a value of "moderator", "participant", or "visitor", and with
     * the 'affiliation' attribute set to a value of "owner", "admin", "member", or "none" as appropriate.
     * </blockquote>
     *
     * <p>From XEP-0045 § 7.2.2:</p>
     * <blockquote>
     * the "self-presence" sent by the room to the new user MUST include a status code of 110 so that the user knows
     * this presence refers to itself as an occupant
     * </blockquote>
     *
     * @throws Exception when errors occur
     */
    @SmackIntegrationTest
    public void mucJoinPresenceInformationTest() throws Exception {
        EntityBareJid mucAddress = getRandomRoom("smack-inttest-presenceinfo");

        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByThree = mucManagerThree.getMultiUserChat(mucAddress);

        final Resourcepart nicknameOne = Resourcepart.from("one-" + randomString);
        final Resourcepart nicknameTwo = Resourcepart.from("two-" + randomString);
        final Resourcepart nicknameThree = Resourcepart.from("three-" + randomString);

        createMuc(mucAsSeenByOne, nicknameOne);
        mucAsSeenByTwo.join(nicknameTwo);
        mucAsSeenByOne.grantModerator(nicknameTwo);

        List<Presence> results = new ArrayList<Presence>();
        mucAsSeenByThree.addParticipantListener(new PresenceListener() {
            @Override public void processPresence(Presence presence) {
                results.add(presence);
            }
        });

        try {
            // Will block until all self-presence is received, prior to which all others presences will have been received.
            mucAsSeenByThree.join(nicknameThree);

            assertEquals(3, results.size()); // The 3rd will be self-presence.
            assertNotNull(MUCUser.from(results.get(0))); // Smack implementation guarantees the "x" element and muc#user namespace
            assertEquals(MUCAffiliation.owner, MUCUser.from(results.get(0)).getItem().getAffiliation());
            assertEquals(MUCAffiliation.none, MUCUser.from(results.get(1)).getItem().getAffiliation());
            assertEquals(MUCAffiliation.none, MUCUser.from(results.get(2)).getItem().getAffiliation());
            assertEquals(MUCRole.moderator, MUCUser.from(results.get(0)).getItem().getRole());
            assertEquals(MUCRole.moderator, MUCUser.from(results.get(1)).getItem().getRole());
            assertEquals(MUCRole.participant, MUCUser.from(results.get(2)).getItem().getRole());
            assertTrue(MUCUser.from(results.get(2)).getStatus().contains(MUCUser.Status.PRESENCE_TO_SELF_110));
        } finally {
            tryDestroy(mucAsSeenByOne);
        }
    }

    /**
     * Asserts that when a user joins a room, all users are sent presence information about the new participant.
     *
     *
     * <p>From XEP-0045 § 7.2.2:</p>
     * <blockquote>
     * the service MUST also send presence from the new participant's occupant JID to the full JIDs of all the
     * occupants (including the new occupant)
     * </blockquote>
     *
     * @throws Exception when errors occur
     */
    @SmackIntegrationTest
    public void mucJoinPresenceBroadcastTest() throws Exception {
        EntityBareJid mucAddress = getRandomRoom("smack-inttest-presenceinfo");

        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByThree = mucManagerThree.getMultiUserChat(mucAddress);

        final Resourcepart nicknameOne = Resourcepart.from("one-" + randomString);
        final Resourcepart nicknameTwo = Resourcepart.from("two-" + randomString);
        final Resourcepart nicknameThree = Resourcepart.from("three-" + randomString);

        createMuc(mucAsSeenByOne, nicknameOne);
        mucAsSeenByTwo.join(nicknameTwo);

        final MultiResultSyncPoint<Presence, Exception> syncPoint = new MultiResultSyncPoint<>(2);

        mucAsSeenByOne.addParticipantListener(presence -> {
            if (nicknameThree.equals(presence.getFrom().getResourceOrEmpty())) {
                syncPoint.signal(presence);
            }
        });

        mucAsSeenByTwo.addParticipantListener(presence -> {
            if (nicknameThree.equals(presence.getFrom().getResourceOrEmpty())) {
                syncPoint.signal(presence);
            }
        });

        try {
            mucAsSeenByThree.join(nicknameThree);

            Collection<Presence> results = syncPoint.waitForResults(timeout);
            assertTrue(results.stream().allMatch(result -> JidCreate.fullFrom(mucAddress, nicknameThree).equals(result.getFrom())));
            assertTrue(results.stream().anyMatch(result -> result.getTo().equals(conOne.getUser().asEntityFullJidIfPossible())));
            assertTrue(results.stream().anyMatch(result -> result.getTo().equals(conTwo.getUser().asEntityFullJidIfPossible())));
        } finally {
            tryDestroy(mucAsSeenByOne);
        }
    }

    /**
     * Asserts that when a user leaves a room, they are themselves included on the list of users notified (self-presence).
     *
     * <p>From XEP-0045 § 7.14:</p>
     * <blockquote>
     * The service MUST then send a presence stanzas of type "unavailable" from the departing user's occupant JID to
     * the departing occupant's full JIDs, including a status code of "110" to indicate that this notification is
     * "self-presence"
     * </blockquote>
     *
     * @throws Exception when errors occur
     */
    @SmackIntegrationTest
    public void mucLeaveTest() throws Exception {
        EntityBareJid mucAddress = getRandomRoom("smack-inttest-leave");

        MultiUserChat muc = mucManagerOne.getMultiUserChat(mucAddress);
        try {
            muc.join(Resourcepart.from("nick-one"));

            Presence reflectedLeavePresence = muc.leave();

            MUCUser mucUser = MUCUser.from(reflectedLeavePresence);
            assertNotNull(mucUser);

            assertTrue(mucUser.getStatus().contains(MUCUser.Status.PRESENCE_TO_SELF_110));
            assertEquals(mucAddress + "/nick-one", reflectedLeavePresence.getFrom().toString());
            assertEquals(conOne.getUser().asEntityFullJidIfPossible().toString(), reflectedLeavePresence.getTo().toString());
        } finally {
            muc.join(Resourcepart.from("nick-one")); // We need to be in the room to destroy the room
            tryDestroy(muc);
        }
    }
}
