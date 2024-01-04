/* Copyright (c) 2023 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common

import com.vesoft.exchange.Argument

import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, KeyPairGenerator, SecureRandom}
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object PasswordEncryption {
  private val algorithm = "RSA"
  private val charset = "UTF-8"

  def main(args: Array[String]): Unit = {
    val passwdOption = new scopt.OptionParser[PasswordConfig]("encrypt password") {
      head("encrypt password")

      opt[String]('p', "passwd")
        .required()
        .valueName("passwd")
        .action((x, c) => c.copy(password = x))
        .text("your real password")
    }.parse(args, PasswordConfig())

    require(passwdOption.isDefined && passwdOption.get.password != null, "lack of password parameter")

    val password:String = passwdOption.get.password

    val (encryptedPasswd, privateKey) = encryptPassword(password)
    println(s"=================== private key begin ===================")
    println(privateKey)
    println(s"=================== private key end ===================\n\n")

    println(s"=================== encrypted  password begin ===================")
    println(encryptedPasswd)
    println(s"=================== encrypted  password end ===================")

    println(s"check: the real password decrypted by private key and encrypted password is: ${decryptPassword(encryptedPasswd, privateKey)}")
  }

  /**
   * encrypt the password
   *
   * @param password real password
   * @return (encryptedPasswd, privateKey)
   */
  def encryptPassword(password: String): (String, String) = {
    val keyPairGenerator = KeyPairGenerator.getInstance(algorithm)
    keyPairGenerator.initialize(1024, new SecureRandom())
    val keyPair = keyPairGenerator.generateKeyPair()
    val privateKey = keyPair.getPrivate
    val privateKeyStr = new String(Base64.getEncoder.encode(privateKey.getEncoded), charset)
    val publicKey = keyPair.getPublic
    val publicKeyStr = new String(Base64.getEncoder.encode(publicKey.getEncoded), charset)
    println(s"=================== public key begin ===================")
    println(publicKeyStr)
    println(s"=================== public key end ===================\n\n")

    // encrypt the password
    val encoded = Base64.getDecoder.decode(publicKeyStr)
    val rsaPublicKey = KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(encoded))
    val cipher = Cipher.getInstance(algorithm)
    cipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey)
    val encodePasswd = new String(Base64.getEncoder.encode(cipher.doFinal(password.getBytes(charset))), charset)
    (encodePasswd, privateKeyStr)
  }

  /**
   * decrypt the encrypted password with private key
   *
   * @param encryptedPassword encrypted password
   * @param privateKey        rsa private key
   * @return real password
   */
  def decryptPassword(encryptedPassword: String, privateKey: String): String = {
    val encryptedPasswdBytes = Base64.getDecoder.decode(encryptedPassword)
    val decodedPrivateKey = Base64.getDecoder.decode(privateKey)
    val rsaPrivateKey = KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(decodedPrivateKey))
    val cipher = Cipher.getInstance(algorithm)
    cipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey)
    val password = new String(cipher.doFinal(encryptedPasswdBytes), charset)
    password
  }


}

final case class PasswordConfig(password: String = null)
