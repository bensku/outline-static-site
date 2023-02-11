package fi.benjami.site.outline;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.crypto.encrypt.AesBytesEncryptor;
import org.springframework.security.crypto.encrypt.AesBytesEncryptor.CipherAlgorithm;
import org.springframework.security.crypto.keygen.KeyGenerators;

public class StringCrypto {

	private final AesBytesEncryptor encryptor;
	
	public StringCrypto(String key) {
		var secretKey = new SecretKeySpec(Hex.decode(key), "AES");
		this.encryptor = new AesBytesEncryptor(secretKey, KeyGenerators.secureRandom(16), CipherAlgorithm.GCM);
	}
	
	public String encrypt(String cleartext) {
		var ciphertext = encryptor.encrypt(cleartext.getBytes(StandardCharsets.UTF_8));
		return Base64.getUrlEncoder().encodeToString(ciphertext);
	}
	
	public String decrypt(String ciphertext) {
		var data = Base64.getUrlDecoder().decode(ciphertext);
		return new String(encryptor.decrypt(data), StandardCharsets.UTF_8);
	}
}
