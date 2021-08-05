/**
 *
 * Copyright 2015-2020 Florian Schmaus
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
package org.igniterealtime.smack.inttest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.igniterealtime.smack.inttest.util.SimpleResultSyncPoint;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.FromMatchesFilter;
import org.jivesoftware.smack.filter.PresenceTypeFilter;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.util.Async.ThrowingRunnable;

public abstract class AbstractSmackIntegrationTest extends AbstractSmackIntTest {

    /**
     * The first connection.
     */
    protected final XMPPConnection conOne;

    /**
     * The second connection.
     */
    protected final XMPPConnection conTwo;

    /**
     * The third connection.
     */
    protected final XMPPConnection conThree;

    /**
     * An alias for the first connection {@link #conOne}.
     */
    protected final XMPPConnection connection;

    protected final List<XMPPConnection> connections;

    public AbstractSmackIntegrationTest(SmackIntegrationTestEnvironment environment) {
        super(environment);
        this.connection = this.conOne = environment.conOne;
        this.conTwo = environment.conTwo;
        this.conThree = environment.conThree;

        final List<XMPPConnection> connectionsLocal = new ArrayList<>(3);
        connectionsLocal.add(conOne);
        connectionsLocal.add(conTwo);
        connectionsLocal.add(conThree);
        this.connections = Collections.unmodifiableList(connectionsLocal);
    }

    /**
     * Perform action and wait until conA observes a presence form conB.
     * <p>
     * This method is usually used so that 'action' performs an operation that changes one entities
     * features/nodes/capabilities, and we want to check that another connection is able to observe this change, and use
     * that new "thing" that was added to the connection.
     * </p>
     * <p>
     * Note that this method is a workaround at best and not reliable. Because it is not guaranteed that any XEP-0030
     * related manager, e.g. EntityCapsManager, already processed the presence when this method returns.
     * </p>
     * TODO: Come up with a better solution.
     *
     * @param conA the connection to observe the presence on.
     * @param conB the connection sending the presence
     * @param action the action to perform.
     * @throws Exception in case of an exception.
     */
    protected void performActionAndWaitForPresence(XMPPConnection conA, XMPPConnection conB, ThrowingRunnable action)
                    throws Exception {
        final SimpleResultSyncPoint presenceReceivedSyncPoint = new SimpleResultSyncPoint();
        final StanzaListener presenceListener = new StanzaListener() {
            @Override
            public void processStanza(Stanza packet) {
                presenceReceivedSyncPoint.signal();
            }
        };

        // Add a stanzaListener to listen for incoming presence
        conA.addAsyncStanzaListener(presenceListener, new AndFilter(
                        PresenceTypeFilter.AVAILABLE,
                        FromMatchesFilter.create(conB.getUser())
                        ));

        action.runOrThrow();

        try {
            // wait for the dummy feature to get sent via presence
            presenceReceivedSyncPoint.waitForResult(timeout);
        } finally {
            conA.removeAsyncStanzaListener(presenceListener);
        }
    }

    /**
     * Execute a script. We can handle complex bash commands including
     * multiple executions (; | && ||), quotes, expansions ($), escapes (\), e.g.:
     *     "cd /abc/def; mv ghi 'older ghi '$(whoami)"
     * For this to work, the configuration parameter "scriptPath" needs to be set properly.
     * @param scriptCommand the script to execute
     * @return the script output
     */
    protected String[] executeScript(String scriptCommand) throws SmackException.ScriptExecutionException {
        final String command = sinttestConfiguration.scriptPath + scriptCommand;
        final List<String> outputLines = new ArrayList<>();
        outputLines.add("Executing BASH command:\n" + command);
        outputLines.add("======================");
        Runtime r = Runtime.getRuntime();
        // Use bash -c so we can handle things like multi commands separated by ; and
        // things like quotes, $, |, and \. Tests show that command comes as
        // one argument to bash, so we do not need to quote it to make it one thing.
        // Also, exec may object if it does not have an executable file as the first thing,
        // so having bash here makes it happy provided bash is installed and in path.
        String[] commands = {"bash", "-c", command};
        try {
            Process p = r.exec(commands);

            p.waitFor();
            BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()));
            String line = "";

            while ((line = b.readLine()) != null) {
                outputLines.add(line);
            }

            b.close();
        } catch (IOException | InterruptedException e) {
            throw new SmackException.ScriptExecutionException(e, outputLines.toArray(new String[0]));
        }

        return outputLines.toArray(new String[0]);
    }

    protected void testScript(int nodeNr) throws SmackException.ScriptExecutionException {
        executeScript("test.sh " + nodeNr);
    }

    protected void disconnectNode(int nodeNr) throws SmackException.ScriptExecutionException {
        executeScript("block_node_from_cluster.sh " + nodeNr);
    }

    protected void reconnectNode(int nodeNr) throws SmackException.ScriptExecutionException {
        executeScript("unblock_node_from_cluster.sh " + nodeNr);
    }
}
