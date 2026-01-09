using System;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using UnityEngine;
using UnityEngine.UI;

public class JpegTcpStreamReceiver : MonoBehaviour
{
    [Header("TCP")]
    public string host = "127.0.0.1";
    public int port = 19091;
    public int connectTimeoutMs = 2000;

    [Header("Output (choose one)")]
    public RawImage targetRawImage;
    public Renderer targetRenderer;

    [Header("Behavior")]
    public bool autoReconnect = true;
    public int reconnectDelayMs = 500;

    private Thread _thread;
    private volatile bool _running;

    private readonly object _frameLock = new object();
    private byte[] _latestJpeg;
    private bool _hasNewFrame;

    private Texture2D _tex;

    void Start()
    {
        _tex = new Texture2D(2, 2, TextureFormat.RGBA32, false);
        ApplyTexture(_tex);

        _running = true;
        _thread = new Thread(ReceiveLoop) { IsBackground = true };
        _thread.Start();
    }

    void Update()
    {
        byte[] frame = null;

        lock (_frameLock)
        {
            if (_hasNewFrame && _latestJpeg != null)
            {
                frame = _latestJpeg;
                _latestJpeg = null;
                _hasNewFrame = false;
            }
        }

        if (frame != null)
        {
            if (_tex.LoadImage(frame))
            {
                ApplyTexture(_tex);
            }
        }
    }

    void OnDestroy()
    {
        _running = false;
        try { _thread?.Join(500); } catch { }
        _thread = null;
    }

    private void ApplyTexture(Texture2D tex)
    {
        if (targetRawImage != null)
        {
            targetRawImage.texture = tex;
            return;
        }
        if (targetRenderer != null)
        {
            targetRenderer.material.mainTexture = tex;
        }
    }

    private void ReceiveLoop()
    {
        while (_running)
        {
            TcpClient client = null;
            NetworkStream ns = null;

            try
            {
                client = new TcpClient();
                var ar = client.BeginConnect(host, port, null, null);
                if (!ar.AsyncWaitHandle.WaitOne(connectTimeoutMs))
                    throw new TimeoutException("Connect timeout");

                client.EndConnect(ar);
                ns = client.GetStream();

                while (_running && client.Connected)
                {
                    int len = ReadInt32BE(ns);
                    if (len <= 0 || len > 50_000_000) // sanity limit: 50MB
                        throw new InvalidDataException($"Invalid frame length: {len}");

                    var jpeg = ReadExact(ns, len);

                    lock (_frameLock)
                    {
                        _latestJpeg = jpeg;
                        _hasNewFrame = true;
                    }
                }
            }
            catch (Exception)
            {
                // Intentionally silent: this is expected during reconnect/disconnect.
            }
            finally
            {
                try { ns?.Close(); } catch { }
                try { client?.Close(); } catch { }
            }

            if (!autoReconnect) break;
            Thread.Sleep(reconnectDelayMs);
        }
    }

    private static int ReadInt32BE(Stream s)
    {
        var b = ReadExact(s, 4);
        return (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3];
    }

    private static byte[] ReadExact(Stream s, int n)
    {
        var buf = new byte[n];
        int off = 0;
        while (off < n)
        {
            int r = s.Read(buf, off, n - off);
            if (r <= 0) throw new EndOfStreamException();
            off += r;
        }
        return buf;
    }
}
