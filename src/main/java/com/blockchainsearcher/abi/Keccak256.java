package com.blockchainsearcher.abi;

import java.nio.charset.StandardCharsets;

/**
 * Minimal Keccak-256 (the original Keccak padding used by Ethereum, not NIST SHA3-256).
 * Used for event topic0 hashes and EIP-55 address checksums.
 */
public final class Keccak256 {

    private static final int RATE_BYTES = 136;

    private static final long[] ROUND_CONSTANTS = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL, 0x8000000080008000L,
            0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
            0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800AL, 0x800000008000000AL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };

    /** Rotation offsets, indexed by x + 5 * y. */
    private static final int[] ROTATIONS = {
            0, 1, 62, 28, 27,
            36, 44, 6, 55, 20,
            3, 10, 43, 25, 39,
            41, 45, 15, 21, 8,
            18, 2, 61, 56, 14
    };

    private Keccak256() {
    }

    public static byte[] hash(byte[] input) {
        long[] state = new long[25];
        int blocks = input.length / RATE_BYTES + 1;
        byte[] padded = new byte[blocks * RATE_BYTES];
        System.arraycopy(input, 0, padded, 0, input.length);
        padded[input.length] ^= 0x01;
        padded[padded.length - 1] ^= (byte) 0x80;

        for (int block = 0; block < blocks; block++) {
            int offset = block * RATE_BYTES;
            for (int i = 0; i < RATE_BYTES / 8; i++) {
                state[i] ^= readLongLittleEndian(padded, offset + i * 8);
            }
            permute(state);
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 4; i++) {
            long lane = state[i];
            for (int b = 0; b < 8; b++) {
                out[i * 8 + b] = (byte) (lane >>> (8 * b));
            }
        }
        return out;
    }

    public static String hashHex(String utf8) {
        return "0x" + Hex.toHex(hash(utf8.getBytes(StandardCharsets.UTF_8)));
    }

    private static long readLongLittleEndian(byte[] data, int offset) {
        long value = 0;
        for (int b = 7; b >= 0; b--) {
            value = (value << 8) | (data[offset + b] & 0xFFL);
        }
        return value;
    }

    private static void permute(long[] s) {
        long[] c = new long[5];
        long[] b = new long[25];
        for (int round = 0; round < 24; round++) {
            // theta
            for (int x = 0; x < 5; x++) {
                c[x] = s[x] ^ s[x + 5] ^ s[x + 10] ^ s[x + 15] ^ s[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
                for (int y = 0; y < 25; y += 5) {
                    s[x + y] ^= d;
                }
            }
            // rho + pi
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    int index = x + 5 * y;
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = Long.rotateLeft(s[index], ROTATIONS[index]);
                }
            }
            // chi
            for (int y = 0; y < 25; y += 5) {
                for (int x = 0; x < 5; x++) {
                    s[x + y] = b[x + y] ^ (~b[(x + 1) % 5 + y] & b[(x + 2) % 5 + y]);
                }
            }
            // iota
            s[0] ^= ROUND_CONSTANTS[round];
        }
    }
}
