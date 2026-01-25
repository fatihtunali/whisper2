/**
 * Type declarations for sodium-native
 */

declare module 'sodium-native' {
  // Constants
  export const crypto_hash_sha256_BYTES: number;
  export const crypto_sign_BYTES: number;
  export const crypto_sign_PUBLICKEYBYTES: number;
  export const crypto_sign_SECRETKEYBYTES: number;
  export const crypto_box_PUBLICKEYBYTES: number;
  export const crypto_box_SECRETKEYBYTES: number;
  export const crypto_box_NONCEBYTES: number;
  export const crypto_secretbox_NONCEBYTES: number;
  export const crypto_secretbox_KEYBYTES: number;

  // Random
  export function randombytes_buf(buffer: Buffer): void;

  // Hash
  export function crypto_hash_sha256(output: Buffer, input: Buffer): void;

  // Sign
  export function crypto_sign_keypair(publicKey: Buffer, secretKey: Buffer): void;
  export function crypto_sign_seed_keypair(
    publicKey: Buffer,
    secretKey: Buffer,
    seed: Buffer
  ): void;
  export function crypto_sign_detached(
    signature: Buffer,
    message: Buffer,
    secretKey: Buffer
  ): void;
  export function crypto_sign_verify_detached(
    signature: Buffer,
    message: Buffer,
    publicKey: Buffer
  ): boolean;

  // Box (X25519)
  export function crypto_box_keypair(publicKey: Buffer, secretKey: Buffer): void;
  export function crypto_box_seed_keypair(
    publicKey: Buffer,
    secretKey: Buffer,
    seed: Buffer
  ): void;
  export function crypto_box_easy(
    ciphertext: Buffer,
    message: Buffer,
    nonce: Buffer,
    publicKey: Buffer,
    secretKey: Buffer
  ): void;
  export function crypto_box_open_easy(
    message: Buffer,
    ciphertext: Buffer,
    nonce: Buffer,
    publicKey: Buffer,
    secretKey: Buffer
  ): boolean;

  // Secretbox
  export function crypto_secretbox_easy(
    ciphertext: Buffer,
    message: Buffer,
    nonce: Buffer,
    key: Buffer
  ): void;
  export function crypto_secretbox_open_easy(
    message: Buffer,
    ciphertext: Buffer,
    nonce: Buffer,
    key: Buffer
  ): boolean;
}
