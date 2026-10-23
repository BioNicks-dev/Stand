package com.bionicks.stand;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

// Bekçinin kendi küçük sunucu bağlantısı (MQTT 3.1.1, sadece dinler).
// Sayfadan bağımsızdır: Android ekran kapalıyken sayfayı yavaşlatsa da bu bağlantı ayakta kalır.
final class Mqtt extends Thread {
    interface Dinleyici {
        void mesaj(String konu, String govde);
        void durum(boolean bagli, String neden);
    }

    private final String host, kul, sifre, konu;
    private final int port;
    private final boolean tls;
    private final Dinleyici d;
    private volatile boolean dur = false;
    private volatile Socket s;
    private long sonGiden, sonGelen;

    Mqtt(String host, int port, boolean tls, String kul, String sifre, String konu, Dinleyici d) {
        super("stand-mqtt");
        this.host = host; this.port = port; this.tls = tls;
        this.kul = kul; this.sifre = sifre; this.konu = konu; this.d = d;
        setDaemon(true);
    }

    void durdur() {
        dur = true;
        try { if (s != null) s.close(); } catch (IOException e) { }
        interrupt();
    }

    @Override public void run() {
        long bekle = 3000;
        while (!dur) {
            boolean bagliydi = false;
            try {
                bagliydi = baglanVeDinle();
            } catch (Exception e) {
                if (!dur) d.durum(false, e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }
            try { if (s != null) s.close(); } catch (IOException e) { }
            if (dur) break;
            bekle = bagliydi ? 3000 : Math.min(bekle * 2, 30000);
            try { Thread.sleep(bekle); } catch (InterruptedException e) { }
        }
    }

    // true: bağlantı kurulmuştu (sonra koptu)
    private boolean baglanVeDinle() throws IOException {
        Socket ham = new Socket();
        ham.connect(new InetSocketAddress(host, port), 10000);
        if (tls) {
            SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(ham, host, port, true);
            SSLParameters p = ss.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ss.setSSLParameters(p);
            ss.startHandshake();
            s = ss;
        } else s = ham;
        s.setSoTimeout(10000);
        DataInputStream in = new DataInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();

        // CONNECT
        ByteArrayOutputStream g = new ByteArrayOutputStream();
        yazStr(g, "MQTT"); g.write(4); g.write(0xC2); g.write(0); g.write(60);
        yazStr(g, "comitok_bekci_" + Long.toHexString(System.nanoTime() & 0xffffffL));
        yazStr(g, kul); yazStr(g, sifre);
        paket(out, 0x10, g.toByteArray());
        int b = in.readUnsignedByte();
        int[] u = new int[1]; byte[] gv = govdeOku(in, u);
        if ((b >> 4) != 2 || gv.length < 2) throw new IOException("beklenmeyen cevap");
        if (gv[1] != 0) throw new IOException("sunucu reddetti (kod " + gv[1] + ")");

        // SUBSCRIBE
        g.reset(); g.write(0); g.write(1); yazStr(g, konu); g.write(0);
        paket(out, 0x82, g.toByteArray());
        d.durum(true, "");
        sonGelen = sonGiden = System.currentTimeMillis();
        s.setSoTimeout(5000);

        while (!dur) {
            int ilk;
            try { ilk = in.readUnsignedByte(); }
            catch (SocketTimeoutException e) {
                long simdi = System.currentTimeMillis();
                if (simdi - sonGelen > 90000) throw new IOException("sunucudan 90 sn cevap yok");
                if (simdi - sonGiden > 25000) { out.write(new byte[]{(byte) 0xC0, 0}); out.flush(); sonGiden = simdi; }
                continue;
            }
            s.setSoTimeout(15000);
            byte[] veri = govdeOku(in, u);
            s.setSoTimeout(5000);
            sonGelen = System.currentTimeMillis();
            if ((ilk >> 4) == 3) {   // PUBLISH
                int tl = ((veri[0] & 0xff) << 8) | (veri[1] & 0xff);
                String k = new String(veri, 2, tl, StandardCharsets.UTF_8);
                int bas = 2 + tl + (((ilk >> 1) & 3) > 0 ? 2 : 0);
                String govde = new String(veri, bas, veri.length - bas, StandardCharsets.UTF_8);
                try { d.mesaj(k, govde); } catch (Exception e) { }
            }
            if (System.currentTimeMillis() - sonGiden > 25000) { out.write(new byte[]{(byte) 0xC0, 0}); out.flush(); sonGiden = System.currentTimeMillis(); }
        }
        return true;
    }

    private static byte[] govdeOku(DataInputStream in, int[] u) throws IOException {
        int carpan = 1, uzun = 0, b;
        do { b = in.readUnsignedByte(); uzun += (b & 127) * carpan; carpan *= 128; } while ((b & 128) != 0 && carpan < 2097152 * 128);
        byte[] v = new byte[uzun];
        in.readFully(v);
        u[0] = uzun;
        return v;
    }

    private void paket(OutputStream out, int tip, byte[] govde) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(tip);
        int x = govde.length;
        do { int e = x % 128; x /= 128; if (x > 0) e |= 128; p.write(e); } while (x > 0);
        p.write(govde);
        out.write(p.toByteArray()); out.flush();
        sonGiden = System.currentTimeMillis();
    }

    private static void yazStr(ByteArrayOutputStream g, String t) {
        byte[] b = t.getBytes(StandardCharsets.UTF_8);
        g.write(b.length >> 8); g.write(b.length & 0xff); g.write(b, 0, b.length);
    }

}
