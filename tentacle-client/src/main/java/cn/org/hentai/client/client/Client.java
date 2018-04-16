package cn.org.hentai.client.client;

import cn.org.hentai.client.worker.CaptureWorker;
import cn.org.hentai.client.worker.CompressWorker;
import cn.org.hentai.client.worker.ScreenImages;
import cn.org.hentai.tentacle.protocol.Command;
import cn.org.hentai.tentacle.protocol.Packet;
import cn.org.hentai.tentacle.util.ByteUtils;
import cn.org.hentai.tentacle.util.Configs;
import cn.org.hentai.tentacle.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Created by matrixy on 2018/4/15.
 */
public class Client extends Thread
{
    // 是否正在发送截图
    boolean working = false;

    Thread captureWorker;
    Thread compressWorker;

    Socket conn;
    InputStream inputStream;
    OutputStream outputStream;

    long lastActiveTime = 0L;

    // 与服务器间的会话处理
    private void converse() throws Exception
    {
        working = false;
        conn = new Socket(Configs.get("server.addr"), Configs.getInt("server.port", 1986));
        conn.setSoTimeout(30000);
        inputStream = conn.getInputStream();
        outputStream = conn.getOutputStream();

        lastActiveTime = System.currentTimeMillis();
        Log.info("Connected to server...");

        // TODO 1. 身份验证
        while (true)
        {
            if (System.currentTimeMillis() - lastActiveTime > 30000) break;
            // 有无下发下来的数据包
            Packet packet = Packet.read(inputStream);
            if (packet != null)
            {
                lastActiveTime = System.currentTimeMillis();
                processCommand(packet);
            }

            // 处理服务器下发的指令
            // 有无需要上报的截图
            if (ScreenImages.hasCompressedScreens())
            {
                lastActiveTime = System.currentTimeMillis();
                sendScreenImages();
                continue;
            }
            sleep(5);
        }
        Log.info("Connection closed...");
    }

    // 处理服务器端下发的指令
    private void processCommand(Packet packet) throws Exception
    {
        packet.skip(6);
        int cmd = packet.nextByte();
        int length = packet.nextInt();
        Packet resp = null;
        // 心跳
        if (cmd == Command.HEARTBEAT)
        {
            resp = Packet.create(Command.COMMON_RESPONSE, 4).addByte((byte)'O').addByte((byte)'J').addByte((byte)'B').addByte((byte)'K');
        }
        // 开始远程控制
        else if (cmd == Command.CONTROL_REQUEST)
        {
            if (working) throw new RuntimeException("Already working on capture screenshots...");
            working = true;

            // TODO: 暂不响应服务器端的控制请求的细节要求，比如压缩方式、带宽、颜色位数等
            int compressMethod = packet.nextByte() & 0xff;
            int bandWidth = packet.nextByte() & 0xff;
            int colorBits = packet.nextByte() & 0xff;

            resp = Packet.create(Command.CONTROL_RESPONSE, 11)
                    .addByte((byte)0x01)                            // 压缩方式
                    .addByte((byte)0x00)                            // 带宽
                    .addByte((byte)0x03)                            // 颜色位数
                    .addLong(System.currentTimeMillis());           // 当前系统时间戳
            (captureWorker = new Thread(new CaptureWorker())).start();
            (compressWorker = new Thread(new CompressWorker())).start();
        }
        // TODO: 键鼠事件处理
        else if (cmd == Command.HID_COMMAND)
        {

        }
        // 停止远程控制
        else if (cmd == Command.CLOSE_REQUEST)
        {
            working = false;
            captureWorker.interrupt();
            compressWorker.interrupt();
        }

        // 发送响应至服务器端
        if (resp != null)
        {
            outputStream.write(resp.getBytes());
            outputStream.flush();
        }
    }

    // 发送压缩后的屏幕截图
    private void sendScreenImages() throws Exception
    {
        if (!working) return;
        Packet p = ScreenImages.getCompressedScreen();
        outputStream.write(p.getBytes());
        outputStream.flush();
    }

    // 关闭连接，中断工作线程
    private void release()
    {
        working = false;
        try { inputStream.close(); } catch(Exception e) { }
        try { outputStream.close(); } catch(Exception e) { }
        try { conn.close(); } catch(Exception e) { }
        try
        {
            captureWorker.interrupt();
        }
        catch(Exception e) { }
        try
        {
            compressWorker.interrupt();
        }
        catch(Exception e) { }
    }

    private void sleep(int ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch(Exception e) { }
    }

    public void run()
    {
        while (true)
        {
            try
            {
                converse();
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                release();
            }
            sleep(5000);
        }
    }
}