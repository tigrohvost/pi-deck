# Attaching an existing GGUF instead of downloading it again

Date: 2026-07-27
Status: implemented in 0.3.0-alpha2

## Problem

A GGUF that already sits on the phone can only be reached through the failure card raised when
Android denies access to a shared copy. That card appears solely on the verification path, which
means it is reachable only for a model the app already believes it has downloaded.

For a model in the `не скачана` state the row offers `Скачать` and nothing else. So a user holding
the exact pinned artifact — copied over USB, left behind by an earlier install, or downloaded in a
browser — has no way to tell the deck about it, and pays for the bytes a second time. On the
device this cost 3.01 GiB for Qwen3.5 4B whose file was already in `Download/PiDeck/models/`.

Everything downstream of the pick already works and is model-agnostic: `requestModelDocument`
opens the system picker, `attachExternalDocument` checks the size, `state()` recognises an external
document by its exact length, and verification then runs SHA-256 and the private install. Only the
entry point is missing.

## Decisions

1. **The affordance lives on the model row.** `не скачана` and `сбой загрузки` get
   `Подключить файл` in the secondary slot, which is empty in both branches today. No new UI
   element, and the choice sits where the user is already looking at that model. The remaining
   branches keep `Удалить исходник`.
2. **The picker opens where the files are.** The initial folder becomes a parameter.
   Access recovery keeps aiming at `Download/PiDeck/incoming`; attaching aims at
   `Download/PiDeck`, from which both `incoming/` and `models/` are one tap away.
3. **A rejected file is reported as a rejected file.** Picking the wrong artifact is the likeliest
   failure of this flow, and the existing access card explains it as a UID change after reinstall,
   which is untrue and sends the user looking in the wrong place. A distinct `ФАЙЛ НЕ ПОДХОДИТ`
   card names the expected and actual size, offers `Выбрать другой файл` and `Скачать`.
4. **The reason for a refusal is data, not prose.** `attachExternalDocument` returns an
   `AttachResult` carrying an `AttachFailure` rather than throwing an `IOException` whose message
   the caller would have to parse. This mirrors `VerifyResult` / `VerificationFailure` already in
   the same class and keeps the size rule in one place.
5. **The rule is a pure function.** The decision itself lives in a static
   `attachFailureOf(contentScheme, sizeKnown, size, expected)`, and `attachExternalDocument` is the
   thin layer that queries the provider and stores the URI. This is how `phaseOf` is already
   factored, and it is what lets the rule be tested on a plain JVM: the project has no Robolectric,
   so anything reachable only through a live `ContentResolver` cannot be covered.

## Attach outcomes

| Failure | Raised when | Surfaced as |
|:--|:--|:--|
| `NONE` | size equals `model.bytes` | verification starts |
| `SIZE_MISMATCH` | any other length | `ФАЙЛ НЕ ПОДХОДИТ`, expected vs actual |
| `NOT_A_DOCUMENT` | URI missing or not `content` | `НУЖЕН ДОСТУП` |
| `UNREADABLE` | provider refuses the size query | `НУЖЕН ДОСТУП` |

`SIZE_MISMATCH` carries the observed byte count so the card can state both numbers. The two
access failures keep the existing card, where its wording is accurate.

## Flow

`Подключить файл` → picker at `Download/PiDeck` → persistable read permission is taken →
`attachExternalDocument`. On `NONE` the row moves to `ждёт проверки SHA-256` on its own, because
`state()` already treats an external document of exact length as a finished source, and the
existing verification path then hashes the file and installs the private `0400` copy. The shared
file is never moved, rewritten or deleted.

Nothing is checked more loosely than before: the pinned size gates the attach, SHA-256 gates the
install, and the private copy remains the only artifact the server opens.

## Testing

`ModelAttachTest` on the JVM pins `attachFailureOf`, alongside the existing `ModelDownloadPhaseTest`:

- a document of exactly the expected length yields `NONE`;
- one byte over or under yields `SIZE_MISMATCH`, including the zero-length and
  larger-than-expected cases, so no size is special-cased into acceptance;
- a non-`content` URI yields `NOT_A_DOCUMENT`, and is decided before the size is consulted;
- an unknown size yields `UNREADABLE` rather than being read as a mismatch, because the two point
  the user at different fixes.

`attachExternalDocument` stores the URI only on `NONE`; that ordering is covered on device rather
than on the JVM, since it needs a live `ContentResolver`.

On device: attach the Qwen3.5 4B artifact already present in `Download/PiDeck/models/` and confirm
it reaches the private store without a download, and that picking the 2B file for the 4B row is
refused by size before any hashing.
