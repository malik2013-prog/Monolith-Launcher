# Security Specification (Phase 0 TDD)

## 1. Data Invariants
1. **Strict Per-User Isolation**: Every document in `/users/{userId}/launch_profiles/{profileId}` and `/users/{userId}/diagnostic_reports/{reportId}` belongs exclusively to `userId`. Only an authenticated user with `request.auth.uid == userId` and `incoming().userId == request.auth.uid` can read, create, update, or delete their own documents.
2. **Path Variable & String Bounds**: All path IDs (`userId`, `profileId`, `reportId`) must match `^[a-zA-Z0-9_\-]+$` and be `<= 128` characters. All strings have strict `.size()` upper bounds; all integer fields have strict domain ranges (`allocatedMemoryMb` between 512 and 16384, `javaMajor` in `[8, 17, 21]`).
3. **Temporal & Identity Immutability**: `userId` and `createdAt` are immutable across updates (`incoming().userId == existing().userId` and `incoming().createdAt == existing().createdAt`). Timestamps must be valid server timestamps (`<= request.time`).

## 2. The "Dirty Dozen" Adversarial Payloads
1. **Unauthenticated Read/Write**: Caller with `request.auth == null` attempts `get`, `list`, `create`, `update`, or `delete` on `/users/alice_123/launch_profiles`.
2. **Cross-User Read/Write**: Authenticated user `bob_456` attempts to read or overwrite `/users/alice_123/launch_profiles/prof_1`.
3. **Identity Spoofing on Create**: User `alice_123` writes to `/users/alice_123/launch_profiles/prof_1` with `userId: "bob_456"`.
4. **Shadow/Ghost Field Injection on Create**: Payload includes an undeclared field `"isAdmin": true`.
5. **Shadow/Ghost Field Injection on Update**: Update payload modifies `"isVerified": true` outside `affectedKeys().hasOnly(...)`.
6. **Immutable Field Mutation (`userId`)**: Update attempts to change `userId` from `"alice_123"` to `"bob_456"`.
7. **Immutable Field Mutation (`createdAt`)**: Update attempts to overwrite `createdAt` with a newer timestamp.
8. **Value Poisoning (`allocatedMemoryMb`)**: Payload supplies a string `"4096M"` or out-of-range integer `9999999` for `allocatedMemoryMb`.
9. **Invalid Enum Value (`status`)**: Diagnostic report supplies `status: "HACKED"` instead of `READY`, `REPAIRED`, or `ACTION_REQUIRED`.
10. **Oversized String Exhaustion**: `customJvmFlags` or `summary` exceeds maximum character length (e.g. 10,000 chars).
11. **Future Timestamp Injection**: Client supplies a forged timestamp in the future (`createdAt > request.time`).
12. **Malformed Document ID**: Path ID contains disallowed characters or exceeds 128 chars.
