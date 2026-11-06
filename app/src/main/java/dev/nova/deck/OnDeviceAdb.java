package dev.nova.deck;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.math.BigInteger;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** A device-local ADB identity, persisted privately so pairing survives app and device restarts. */
final class OnDeviceAdb extends AbsAdbConnectionManager {
    private static OnDeviceAdb instance;
    private final PrivateKey privateKey;
    private final Certificate certificate;

    static synchronized OnDeviceAdb get(Context context) throws Exception {
        if (instance == null) instance = new OnDeviceAdb(context.getApplicationContext());
        return instance;
    }

    private OnDeviceAdb(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        ConscryptInit.install();
        File keyFile=new File(context.getFilesDir(),"adb-host.pk8");
        File certFile=new File(context.getFilesDir(),"adb-host.pem");
        if(keyFile.isFile()&&certFile.isFile()) {
            privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(read(keyFile)));
            try(InputStream input=new FileInputStream(certFile)){certificate=CertificateFactory.getInstance("X.509").generateCertificate(input);}
        } else {
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048,SecureRandom.getInstance("SHA1PRNG"));
            KeyPair pair=generator.generateKeyPair();
            privateKey=pair.getPrivate();
            org.bouncycastle.asn1.x500.X500Name name=new org.bouncycastle.asn1.x500.X500Name("CN=DuoDash on this device");
            Date from=new Date(System.currentTimeMillis()-86400000L),until=new Date(System.currentTimeMillis()+10L*365*86400000);
            JcaX509v3CertificateBuilder certBuilder=new JcaX509v3CertificateBuilder(name,new BigInteger(63,new SecureRandom()),from,until,name,pair.getPublic());
            certBuilder.addExtension(Extension.subjectKeyIdentifier,false,new JcaX509ExtensionUtils().createSubjectKeyIdentifier(pair.getPublic()));
            certificate=new JcaX509CertificateConverter().getCertificate(certBuilder.build(new JcaContentSignerBuilder("SHA512withRSA").build(privateKey)));
            writePrivate(keyFile,privateKey.getEncoded());writeCertificate(certFile,certificate.getEncoded());
        }
    }

    @Override protected PrivateKey getPrivateKey(){return privateKey;}
    @Override protected Certificate getCertificate(){return certificate;}
    @Override protected String getDeviceName(){return "DuoDash on this device";}
    String certificateFingerprint()throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());StringBuilder value=new StringBuilder();for(byte b:hash)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    static synchronized void resetForValidation(){if(instance!=null){try{instance.close();}catch(Exception ignored){}instance=null;}}

    private static byte[] read(File file)throws IOException{try(InputStream input=new FileInputStream(file)){return input.readAllBytes();}}
    private static void writePrivate(File file,byte[] bytes)throws IOException{try(FileOutputStream output=new FileOutputStream(file)){output.write(bytes);output.getFD().sync();}file.setReadable(false,false);file.setWritable(false,false);file.setReadable(true,true);file.setWritable(true,true);}
    private static void writeCertificate(File file,byte[] der)throws IOException{
        try(FileOutputStream output=new FileOutputStream(file)){output.write(der);output.flush();output.getFD().sync();}
        file.setReadable(false,false);file.setWritable(false,false);file.setReadable(true,true);file.setWritable(true,true);
    }
    private static final class ConscryptInit {
        private static boolean installed;
        static synchronized void install(){if(!installed){if(Security.getProvider("Conscrypt")==null)Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(),1);installed=true;}}
    }
}
