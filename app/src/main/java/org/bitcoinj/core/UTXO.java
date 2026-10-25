/*
 * Copyright 2012 Matt Corallo.
 * Copyright 2021 Andreas Schildbach
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

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.script.Script;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

// TODO: Fix this class: should not talk about addresses, height should be optional/support mempool height etc

/**
 * A UTXO message contains the information necessary to check a spending transaction.
 * It avoids having to store the entire parentTransaction just to get the hash and index.
 * Useful when working with freestanding outputs.
 */
public class UTXO {
    private final Coin value;
    @javax.annotation.Nullable private final Script script;
    /** Exact scriptPubKey bytes from the transaction; retained even when parsing fails. */
    @javax.annotation.Nullable private final byte[] scriptBytes;
    private final Sha256Hash hash;
    private final long index;
    private final int height;
    private final boolean coinbase;
    private final String address;

    /**
     * Creates a stored transaction output.
     *
     * @param hash     The hash of the containing transaction.
     * @param index    The outpoint.
     * @param value    The value available.
     * @param height   The height this output was created in.
     * @param coinbase The coinbase flag.
     */
    public UTXO(Sha256Hash hash,
                long index,
                Coin value,
                int height,
                boolean coinbase,
                Script script) {
        this(hash, index, value, height, coinbase, script, "");
    }

    /**
     * Creates a stored transaction output.
     *
     * @param hash     The hash of the containing transaction.
     * @param index    The outpoint.
     * @param value    The value available.
     * @param height   The height this output was created in.
     * @param coinbase The coinbase flag.
     * @param address  The address.
     */
    public UTXO(Sha256Hash hash,
                long index,
                Coin value,
                int height,
                boolean coinbase,
                Script script,
                String address) {
        this(hash, index, value, height, coinbase, script,
                script == null ? null : script.getProgram(), address);
    }

    private UTXO(Sha256Hash hash, long index, Coin value, int height, boolean coinbase,
                 @javax.annotation.Nullable Script script, @javax.annotation.Nullable byte[] scriptBytes,
                 String address) {
        this.hash = Objects.requireNonNull(hash);
        this.index = index;
        this.value = Objects.requireNonNull(value);
        this.height = height;
        this.script = script;
        this.scriptBytes = scriptBytes == null ? null : Arrays.copyOf(scriptBytes, scriptBytes.length);
        this.coinbase = coinbase;
        this.address = address;
    }

    /**
     * Reconstruct a chainstate UTXO from the exact serialized scriptPubKey bytes. The UTXO set
     * must retain outputs whose scripts are unspendable because parsing/execution would fail:
     * Bitcoin Core excludes OP_RETURN outputs, not every script the local parser cannot parse.
     * Such an output is kept in the chainstate for correct BIP30/undo semantics but cannot be
     * spent by this interpreter because getScript() returns null.
     */
    public static UTXO fromScriptBytes(Sha256Hash hash, long index, Coin value, int height,
                                       boolean coinbase, @javax.annotation.Nullable byte[] scriptBytes,
                                       String address) {
        Script parsed = null;
        if (scriptBytes != null && scriptBytes.length <= 10_000) {
            try {
                parsed = Script.parse(scriptBytes);
            } catch (RuntimeException ignored) {
                // Preserve the raw bytes; a malformed script is not an executable Script object.
            }
        }
        return new UTXO(hash, index, value, height, coinbase, parsed, scriptBytes, address);
    }

    /** The value which this Transaction output holds. */
    public Coin getValue() {
        return value;
    }

    /** The Script object which you can use to get address, script bytes or script type. */
    public Script getScript() {
        return script;
    }

    /** Exact scriptPubKey bytes, including bytes for outputs the Script parser cannot decode. */
    @javax.annotation.Nullable
    public byte[] getScriptBytes() {
        return scriptBytes == null ? null : Arrays.copyOf(scriptBytes, scriptBytes.length);
    }

    /** The hash of the transaction which holds this output. */
    public Sha256Hash getHash() {
        return hash;
    }

    /** The index of this output in the transaction which holds it. */
    public long getIndex() {
        return index;
    }

    /** Gets the height of the block that created this output. */
    public int getHeight() {
        return height;
    }

    /** Gets the flag of whether this was created by a coinbase tx. */
    public boolean isCoinbase() {
        return coinbase;
    }

    /** The address of this output, can be the empty string if none was provided at construction time or was deserialized */
    public String getAddress() {
        return address;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "Stored TxOut of %s (%s:%d)", value.toFriendlyString(), hash, index);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getIndex(), getHash(), getValue());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UTXO other = (UTXO) o;
        return getIndex() == other.getIndex() && getHash().equals(other.getHash()) && getValue().equals(((UTXO) o).getValue());
    }
}
