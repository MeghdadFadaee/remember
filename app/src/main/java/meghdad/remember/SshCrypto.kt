package meghdad.remember

import com.jcraft.jsch.JSch

/** Use the lightweight BC primitives directly, without replacing Android's JCA providers. */
object SshCrypto {
    init {
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
    }
    fun client(): JSch = JSch()
}
