# Authentication self-check (offline)

This document exists for one reason: the account owner has no machine that can run the
Minecraft Java launcher. Nothing here needs a paid account, a launcher, a game client, a
computer, or even network access. Everything below is checked by `AuthVerificationTest`,
which runs in every CI matrix cell.

## The honest boundary

A full paid-account login has two halves:

| Half | Who does it | Can it be checked here |
| --- | --- | --- |
| Client `/join` with an access token | official launcher | **No** — needs a machine with the launcher |
| Server `hasJoined` verification | folio | **Yes** — fully checked offline |

There is no third place where the account or its password is needed. A Java server never
receives the account credentials; it only receives a username and a `serverId` hash. Giving
this project an account (or a token) would not make the check possible and would not be
stored.

So: the server half is verified. The client half is the launcher's job and is **not claimed
as verified**. That is an environmental limit, not an unfinished code path.

## Two bugs that actually mattered

Found while replacing "you can verify it locally" with an offline check.

### 1. The `serverId` minus sign was being stripped

Minecraft does not use a normal SHA-1 hex digest. The 20 digest bytes are read as a
**signed** big-endian two's-complement integer and printed in base 16, with a leading `-`
when negative. This is exactly Java's `new BigInteger(digest).toString(16)`. The old code
removed the minus sign.

Consequence: for roughly half of all logins the computed `serverId` would be wrong, so
`hasJoined` would return 204 and a genuine paid account would be kicked with
"Failed to verify username!".

Published vectors, now asserted as tests:

| Input | Minecraft digest |
| --- | --- |
| `Notch` | `4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48` |
| `jeb_` | `-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1` |
| `simon` | `88e16a1019277b15d58faf0541e11910eb756f6` |

The `jeb_` case is the one that catches the bug.

### 2. HTTP 204 was treated as success

`hasJoined` returns **200 with a profile** only when the client completed `/join`.
**204 No Content means there is no such session** (stale token, IP mismatch, or the client
never called `/join`) and authentication must be refused. The old code returned an offline
UUID on 204 — effectively letting any unauthenticated client into an `online-mode` server.

Now: 204, 403, an empty body, a missing profile id, or a profile name that does not match
the login name all disconnect with "Failed to verify username!". Only 200 passes, and the
UUID and canonical name returned by Mojang are the ones sent back in Login Success.

## What the offline tests assert

1. The three official digest vectors, including the negative one.
2. A negative digest keeps its 41-character `-...` form.
3. `loginHash` equals SHA-1(serverId + shared secret + X.509 public key) fed through the
   signed digest function, minus sign intact.
4. RSA/PKCS#1 round-trips for both the shared secret and the verify token.
5. AES/CFB8 is a **continuous** stream: the ciphertext of two consecutive frames
   concatenated decrypts back to both plaintext frames, including each length prefix and
   packet id. The cipher is not reset per packet.
6. A 200 response yields the canonical profile name and the correct query parameters.
7. 204, 403, and a name-mismatch 200 each produce a Login Disconnect, not Login Success.

Test file: `src/test/java/net/de5/rainbowcrrsys/folio/AuthVerificationTest.java`.
Session-service stand-in: `MockSessionServer.java` (loopback only, no real Mojang calls).

## If a machine becomes available

No code changes and no credentials sent anywhere. Just:

```sh
java -jar folio.jar                 # eula.txt already set to true, online-mode: true
# official launcher, 26.3 client, add server 127.0.0.1:25565, join
```

If the loading screen hangs, that is the minimal built-in registry set, not authentication.
Export the full table with the official server's `--reports` and place `registries.json`
next to `config.yaml` (see README.md).
