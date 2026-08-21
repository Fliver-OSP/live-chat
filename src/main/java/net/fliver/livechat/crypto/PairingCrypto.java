package net.fliver.livechat.crypto;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts/decrypts the plugin's local pairing state with AES-128-GCM. The
 * key is generated once per install and stored in its own file
 * (pairing.key, next to pairing.dat) rather than embedded in the jar - a
 * key baked into the jar would be identical (and recoverable) on every
 * install, so anyone who could read pairing.dat off disk could also decrypt
 * it just by reading the public jar. On-disk pairing.dat is a single
 * Base64 line: [12-byte IV][ciphertext + 16-byte GCM tag].
 */
public final class PairingCrypto {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int IV_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final int KEY_LEN = 16;

  private volatile byte[] key;

  public synchronized void init(File dataFolder) throws Exception {
    if (key != null) return;

    File keyFile = new File(dataFolder, "pairing.key");
    if (keyFile.isFile()) {
      byte[] existing =
          Base64.getDecoder()
              .decode(new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.UTF_8).trim());
      if (existing.length == KEY_LEN) {
        key = existing;
        return;
      }
    }

    byte[] generated = new byte[KEY_LEN];
    new SecureRandom().nextBytes(generated);
    if (!dataFolder.exists()) {
      dataFolder.mkdirs();
    }
    Files.write(keyFile.toPath(), Base64.getEncoder().encode(generated));
    key = generated;
  }

  public String encrypt(String plaintext) throws Exception {
    byte[] iv = new byte[IV_LEN];
    new SecureRandom().nextBytes(iv);

    Cipher cipher = Cipher.getInstance(ALGORITHM);
    cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
    byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

    byte[] blob = new byte[IV_LEN + ciphertext.length];
    System.arraycopy(iv, 0, blob, 0, IV_LEN);
    System.arraycopy(ciphertext, 0, blob, IV_LEN, ciphertext.length);
    return Base64.getEncoder().encodeToString(blob);
  }

  public String decrypt(String base64) throws Exception {
    byte[] blob = Base64.getDecoder().decode(base64);
    byte[] iv = new byte[IV_LEN];
    System.arraycopy(blob, 0, iv, 0, IV_LEN);
    byte[] ciphertext = new byte[blob.length - IV_LEN];
    System.arraycopy(blob, IV_LEN, ciphertext, 0, ciphertext.length);

    Cipher cipher = Cipher.getInstance(ALGORITHM);
    cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
    return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
  }

  private SecretKey secretKey() {
    byte[] k = key;
    if (k == null) {
      throw new IllegalStateException("PairingCrypto.init(dataFolder) was not called before use.");
    }
    return new SecretKeySpec(k, "AES");
  }
}
