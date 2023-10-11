package org.whispersystems.signalservice.api.crypto;

import org.conscrypt.Conscrypt;
import org.junit.Test;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.incrementalmac.ChunkSizeChoice;
import org.signal.libsignal.protocol.incrementalmac.InvalidMacException;
import org.signal.libsignal.protocol.kdf.HKDFv3;
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream;
import org.whispersystems.signalservice.internal.push.http.AttachmentCipherOutputStreamFactory;
import org.whispersystems.signalservice.internal.util.Util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Security;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.whispersystems.signalservice.testutil.LibSignalLibraryUtil.assumeLibSignalSupportedOnOS;

public final class AttachmentCipherTest {

  static {
    Security.insertProviderAt(Conscrypt.newProvider(), 1);
  }

  @Test
  public void attachment_encryptDecrypt() throws IOException, InvalidMessageException {
    byte[]          key             = Util.getSecretBytes(64);
    byte[]          plaintextInput  = "Peter Parker".getBytes();
    EncryptResult   encryptResult   = encryptData(plaintextInput, key, false);
    File            cipherFile      = writeToFile(encryptResult.ciphertext);
    InputStream     inputStream     = AttachmentCipherInputStream.createForAttachment(cipherFile, plaintextInput.length, key, encryptResult.digest, encryptResult.incrementalDigest, encryptResult.chunkSizeChoice);
    byte[]          plaintextOutput = readInputStreamFully(inputStream);

    assertArrayEquals(plaintextInput, plaintextOutput);

    cipherFile.delete();
  }

  @Test
  public void attachment_encryptDecryptEmpty() throws IOException, InvalidMessageException {
    byte[]          key             = Util.getSecretBytes(64);
    byte[]          plaintextInput  = "".getBytes();
    EncryptResult   encryptResult   = encryptData(plaintextInput, key, true);
    File            cipherFile      = writeToFile(encryptResult.ciphertext);
    InputStream     inputStream     = AttachmentCipherInputStream.createForAttachment(cipherFile, plaintextInput.length, key, encryptResult.digest, encryptResult.incrementalDigest, encryptResult.chunkSizeChoice);
    byte[]          plaintextOutput = readInputStreamFully(inputStream);

    assertArrayEquals(plaintextInput, plaintextOutput);

    cipherFile.delete();
  }

  @Test
  public void attachment_decryptFailOnBadKey() throws IOException {
    File    cipherFile          = null;
    boolean hitCorrectException = false;

    try {
      byte[]        key            = Util.getSecretBytes(64);
      byte[]        plaintextInput = "Gwen Stacy".getBytes();
      EncryptResult encryptResult  = encryptData(plaintextInput, key, true);
      byte[]        badKey         = new byte[64];

      cipherFile = writeToFile(encryptResult.ciphertext);

      AttachmentCipherInputStream.createForAttachment(cipherFile, plaintextInput.length, badKey, encryptResult.digest, null, 0);
    } catch (InvalidMessageException e) {
      hitCorrectException = true;
    } finally {
      if (cipherFile != null) {
        cipherFile.delete();
      }
    }

    assertTrue(hitCorrectException);
  }

  @Test
  public void attachment_decryptFailOnBadDigest() throws IOException {
    File    cipherFile          = null;
    boolean hitCorrectException = false;

    try {
      byte[]        key            = Util.getSecretBytes(64);
      byte[]        plaintextInput = "Mary Jane Watson".getBytes();
      EncryptResult encryptResult  = encryptData(plaintextInput, key, true);
      byte[]        badDigest      = new byte[32];

      cipherFile = writeToFile(encryptResult.ciphertext);

      AttachmentCipherInputStream.createForAttachment(cipherFile, plaintextInput.length, key, badDigest, null, 0);
    } catch (InvalidMessageException e) {
      hitCorrectException = true;
    } finally {
      if (cipherFile != null) {
        cipherFile.delete();
      }
    }

    assertTrue(hitCorrectException);
  }

  @Test
  public void attachment_decryptFailOnBadIncrementalDigest() throws IOException {
    File    cipherFile          = null;
    boolean hitCorrectException = false;

    try {
      byte[]        key            = Util.getSecretBytes(64);
      byte[]        plaintextInput = new byte[1000000];

      new Random().nextBytes(plaintextInput);

      EncryptResult encryptResult  = encryptData(plaintextInput, key, true);
      byte[]        badDigest      = Util.getSecretBytes(encryptResult.incrementalDigest.length);

      cipherFile = writeToFile(encryptResult.ciphertext);


      InputStream decryptedStream = AttachmentCipherInputStream.createForAttachment(cipherFile, plaintextInput.length, key, encryptResult.digest, badDigest, encryptResult.chunkSizeChoice);
      byte[]      plaintextOutput = readInputStreamFully(decryptedStream);
      fail();
    } catch (InvalidMacException e) {
      hitCorrectException = true;
    } catch (InvalidMessageException e) {
      hitCorrectException = false;
    } finally {
      if (cipherFile != null) {
        cipherFile.delete();
      }
    }

    assertTrue(hitCorrectException);
  }

  @Test
  public void attachment_encryptDecryptPaddedContent() throws IOException, InvalidMessageException {
    int[] lengths = { 531, 600, 724, 1019, 1024 };

    for (int length : lengths) {
      byte[] plaintextInput = new byte[length];

      for (int i = 0; i < length; i++) {
        plaintextInput[i] = (byte) 0x97;
      }

      byte[]                key                     = Util.getSecretBytes(64);
      byte[]                iv                      = Util.getSecretBytes(16);
      ByteArrayInputStream  inputStream             = new ByteArrayInputStream(plaintextInput);
      InputStream           paddedInputStream       = new PaddingInputStream(inputStream, length);
      ByteArrayOutputStream destinationOutputStream = new ByteArrayOutputStream();

      DigestingOutputStream encryptingOutputStream        = new AttachmentCipherOutputStreamFactory(key, iv).createFor(destinationOutputStream);

      Util.copy(paddedInputStream, encryptingOutputStream);

      encryptingOutputStream.flush();
      encryptingOutputStream.close();

      byte[] encryptedData     = destinationOutputStream.toByteArray();
      byte[] digest            = encryptingOutputStream.getTransmittedDigest();

      File cipherFile = writeToFile(encryptedData);

      InputStream decryptedStream = AttachmentCipherInputStream.createForAttachment(cipherFile, length, key, digest, null, 0);
      byte[]      plaintextOutput = readInputStreamFully(decryptedStream);

      assertArrayEquals(plaintextInput, plaintextOutput);

      cipherFile.delete();
    }
  }

  @Test
  public void attachment_decryptFailOnNullDigest() throws IOException {
    File    cipherFile          = null;
    boolean hitCorrectException = false;

    try {
      byte[]        key            = Util.getSecretBytes(64);
      byte[]        plaintextInput = "Aunt May".getBytes();
      ChunkSizeChoice sizeChoice      = ChunkSizeChoice.inferChunkSize(plaintextInput.length);
      EncryptResult encryptResult  = encryptData(plaintextInput, key, true);

      cipherFile = writeToFile(encryptResult.ciphertext);

      AttachmentCipherInputStream.createForAttachment(cipherFile, plaintextInput.length, key, null, encryptResult.incrementalDigest, encryptResult.chunkSizeChoice);
    } catch (InvalidMessageException e) {
      hitCorrectException = true;
    } finally {
      if (cipherFile != null) {
        cipherFile.delete();
      }
    }

    assertTrue(hitCorrectException);
  }

  @Test
  public void attachment_decryptFailOnBadMac() throws IOException {
    File    cipherFile          = null;
    boolean hitCorrectException = false;

    try {
      byte[]        key              = Util.getSecretBytes(64);
      byte[]        plaintextInput   = "Uncle Ben".getBytes();
      EncryptResult encryptResult    = encryptData(plaintextInput, key, true);
      byte[]        badMacCiphertext = Arrays.copyOf(encryptResult.ciphertext, encryptResult.ciphertext.length);

      badMacCiphertext[badMacCiphertext.length - 1] += 1;

      cipherFile = writeToFile(badMacCiphertext);

      AttachmentCipherInputStream.createForAttachment(cipherFile, plaintextInput.length, key, encryptResult.digest, null, encryptResult.chunkSizeChoice);
      fail();
    } catch (InvalidMessageException e) {
      hitCorrectException = true;
    } finally {
      if (cipherFile != null) {
        cipherFile.delete();
      }
    }

    assertTrue(hitCorrectException);
  }

  @Test
  public void sticker_encryptDecrypt() throws IOException, InvalidMessageException {
    assumeLibSignalSupportedOnOS();

    byte[]        packKey         = Util.getSecretBytes(32);
    byte[]        plaintextInput  = "Peter Parker".getBytes();
    EncryptResult encryptResult   = encryptData(plaintextInput, expandPackKey(packKey), true);
    InputStream   inputStream     = AttachmentCipherInputStream.createForStickerData(encryptResult.ciphertext, packKey);
    byte[]        plaintextOutput = readInputStreamFully(inputStream);

    assertArrayEquals(plaintextInput, plaintextOutput);
  }

  @Test
  public void sticker_encryptDecryptEmpty() throws IOException, InvalidMessageException {
    assumeLibSignalSupportedOnOS();

    byte[]        packKey         = Util.getSecretBytes(32);
    byte[]        plaintextInput  = "".getBytes();
    EncryptResult encryptResult   = encryptData(plaintextInput, expandPackKey(packKey), true);
    InputStream   inputStream     = AttachmentCipherInputStream.createForStickerData(encryptResult.ciphertext, packKey);
    byte[]        plaintextOutput = readInputStreamFully(inputStream);

    assertArrayEquals(plaintextInput, plaintextOutput);
  }

  @Test
  public void sticker_decryptFailOnBadKey() throws IOException {
    assumeLibSignalSupportedOnOS();

    boolean hitCorrectException = false;

    try {
      byte[]        packKey        = Util.getSecretBytes(32);
      byte[]        plaintextInput = "Gwen Stacy".getBytes();
      EncryptResult encryptResult  = encryptData(plaintextInput, expandPackKey(packKey), true);
      byte[]        badPackKey     = new byte[32];

      AttachmentCipherInputStream.createForStickerData(encryptResult.ciphertext, badPackKey);
    } catch (InvalidMessageException e) {
      hitCorrectException = true;
    }

    assertTrue(hitCorrectException);
  }

  @Test
  public void sticker_decryptFailOnBadMac() throws IOException {
    assumeLibSignalSupportedOnOS();

    boolean hitCorrectException = false;

    try {
      byte[]        packKey          = Util.getSecretBytes(32);
      byte[]        plaintextInput   = "Uncle Ben".getBytes();
      EncryptResult encryptResult    = encryptData(plaintextInput, expandPackKey(packKey), true);
      byte[]        badMacCiphertext = Arrays.copyOf(encryptResult.ciphertext, encryptResult.ciphertext.length);

      badMacCiphertext[badMacCiphertext.length - 1] += 1;

      AttachmentCipherInputStream.createForStickerData(badMacCiphertext, packKey);
    } catch (InvalidMessageException e) {
      hitCorrectException = true;
    }

    assertTrue(hitCorrectException);
  }

  private static EncryptResult encryptData(byte[] data, byte[] keyMaterial, boolean withIncremental) throws IOException {
    ByteArrayOutputStream               outputStream         = new ByteArrayOutputStream();
    ByteArrayOutputStream               incrementalDigestOut = new ByteArrayOutputStream();
    byte[]                              iv                   = Util.getSecretBytes(16);
    AttachmentCipherOutputStreamFactory factory              = new AttachmentCipherOutputStreamFactory(keyMaterial, iv);

    DigestingOutputStream encryptStream;
    final ChunkSizeChoice sizeChoice = ChunkSizeChoice.inferChunkSize(data.length);
    if (withIncremental) {
      encryptStream = factory.createIncrementalFor(outputStream, data.length, sizeChoice, incrementalDigestOut);
    } else {
      encryptStream = factory.createFor(outputStream);
    }


    encryptStream.write(data);
    encryptStream.flush();
    encryptStream.close();
    incrementalDigestOut.close();

    return new EncryptResult(outputStream.toByteArray(), encryptStream.getTransmittedDigest(), incrementalDigestOut.toByteArray(), sizeChoice.getSizeInBytes());
  }

  private static File writeToFile(byte[] data) throws IOException {
    File         file         = File.createTempFile("temp", ".data");
    OutputStream outputStream = new FileOutputStream(file);

    outputStream.write(data);
    outputStream.close();

    return file;
  }

  private static byte[] readInputStreamFully(InputStream inputStream) throws IOException {
    return Util.readFullyAsBytes(inputStream);
  }

  private static byte[] expandPackKey(byte[] shortKey) {
    return new HKDFv3().deriveSecrets(shortKey, "Sticker Pack".getBytes(), 64);
  }

  private static class EncryptResult {
    final byte[] ciphertext;
    final byte[] digest;
    final byte[] incrementalDigest;
    final int    chunkSizeChoice;

    private EncryptResult(byte[] ciphertext, byte[] digest, byte[] incrementalDigest, int chunkSizeChoice) {
      this.ciphertext        = ciphertext;
      this.digest            = digest;
      this.incrementalDigest = incrementalDigest;
      this.chunkSizeChoice   = chunkSizeChoice;
    }
  }
}
