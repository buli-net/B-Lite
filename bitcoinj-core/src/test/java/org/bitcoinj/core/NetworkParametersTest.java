/*
 * Copyright by the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.core;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.params.SigNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.utils.VersionTally;
import org.junit.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Test NetworkParameters
 */
public class NetworkParametersTest {
    @Test
    public void signetFirstVersionedBlockDoesNotUnboxNullVersionTally() {
        NetworkParameters params = SigNetParams.get();
        VersionTally tally = new VersionTally(params);
        // Signet blocks can signal version 4+ immediately after genesis, while the
        // majority window is not yet populated. This must not crash validation.
        Block block = new Block(Block.BLOCK_VERSION_BIP65, Instant.now(), 0x1e0377aeL,
                Collections.emptyList());

        EnumSet<Script.VerifyFlag> flags =
                params.getTransactionVerificationFlags(block, new Transaction(), tally, 1);

        assertTrue(flags.contains(Script.VerifyFlag.P2SH));
        assertFalse(flags.contains(Script.VerifyFlag.CHECKLOCKTIMEVERIFY));
    }

    @Test
    public void deprecatedMembers() {
        NetworkParameters params = NetworkParameters.of(BitcoinNetwork.MAINNET);
        assertEquals(70000, params.getProtocolVersionNum(ProtocolVersion.MINIMUM));
    }
}
