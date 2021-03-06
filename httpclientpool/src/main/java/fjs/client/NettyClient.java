package fjs.client;

import fjs.clientpool.ClientPool;
import fjs.clientpool.NettyClientPool;
import fjs.future.Future;
import fjs.http.Request;
import fjs.http.Response;
import fjs.netty.http.HandlerInitializer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;

import java.lang.Thread;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;


/**
 * Created by fjs on 14-6-20.
 */
public class NettyClient extends AbstractClient{
    private NioEventLoopGroup group;
    private volatile NioSocketChannel channel;

    public NettyClient(ClientPool pool) {
        super(pool);
    }

    private AtomicInteger connectNumber = new AtomicInteger(0);


    /**
     * 处理请求
     *
     * @param request
     * @return
     */
    @Override
    public void request(Request request) {
        if (this.getStatus().equals(ClientStatus.Ready)) {
            this.setStatus(ClientStatus.Working);
        } else {
            Logger.getGlobal().warning("client状态不对啊");
            return;
        }
        QueryStringEncoder encoder = new QueryStringEncoder("http://" + this.getRemoteHost() + request.getPath());
        URI uriGet = null;
        try {
            uriGet = new URI(encoder.toString());
            //System.out.println(uriGet);
        } catch (URISyntaxException e) {
            System.out.println("我擦，，，，");
        }

        FullHttpRequest nr = null;

        if (request.getMethod().equals(Request.RequestMethod.GET)) {
            nr = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uriGet.getRawPath());
        } else if (request.getMethod().equals(Request.RequestMethod.POST)) {
            nr = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uriGet.getRawPath());
            nr.content().writeBytes(request.getBody());
            nr.headers().add("content-length", nr.content().readableBytes());
        }


        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            nr.headers().add(header.getKey(), header.getValue());
        }

        nr.headers().add("Host", this.getRemoteHost());
        nr.headers().add("Connection", "keep-alive");
        this.channel.pipeline().write(nr);
        this.channel.pipeline().flush();
    }

    /**
     * 取消正在运行的request
     */
    @Override
    public void cancel() {
        if (this.getStatus().equals(ClientStatus.Working)) {
            Logger.getGlobal().warning("正在工作的request被取消了");
            this.channel.close();
            this.start();
        }
    }

    /**
     * 当前的链接除了异常
     * @param t
     * @return
     */
    public synchronized void exception(Throwable t) {
        if (this.getStatus().equals(ClientStatus.Stopped)) {
            return;
        }
        Logger.getGlobal().warning("链接出现了异常,将会进行重链接");
        if (this.getStatus().equals(ClientStatus.Ready)) {
            this.getClientPool().removeClient(this);
        } else if (this.getStatus().equals(ClientStatus.Working)) {
            Logger.getGlobal().severe("request执行时出现异常, request is :" + this.getRequest());
            this.getRequest().getFuture().exception(t);
        }
        this.start();  //重启
    }

    /**
     * 当链接断开了之后调用的方法
     */
    public synchronized void disconnected() {
        if (this.getStatus().equals(ClientStatus.Stopped)) {
            return;
        }
        Logger.getGlobal().warning("链接断开了，将会尝试重新链接");
        if (this.getStatus().equals(ClientStatus.Ready)) {
            this.getClientPool().removeClient(this);
        } else if (this.getStatus().equals(ClientStatus.Working)) {
            Logger.getGlobal().severe("request执行时出现异常, request is :" + this.getRequest());
            this.getRequest().getFuture().exception(new Exception("链接断开了"));
        }
        this.start();  //重启
    }

    /**
     * 这里主要是创建channel，然后将其注册到group上面去，另外要看连接是否成功
     * 如果连接失败的话要调度重新建立链接，另外还要控制重连接的数量，如果超过十次还不成功那就算了吧
     * 启动组件
     */
    @Override
    public void start() {
        this.setStatus(ClientStatus.Startting);
        if (this.channel != null) {
            this.channel.close();
            this.channel = null;
        }
        this.channel = new NioSocketChannel();
        this.channel.pipeline().addFirst(new HandlerInitializer(this));
        this.group.register(this.channel);
        this.channel.connect(new InetSocketAddress(this.getRemoteHost(), 80)).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
            if (channelFuture.channel().isActive()) {
                connectNumber.set(0);  //链接上了之后就可以刷新成0
                Logger.getGlobal().info("连接建立成功, host : " + getRemoteHost());
            } else {
                int number = connectNumber.incrementAndGet();
                if (number > 10) {
                    Logger.getGlobal().severe("我擦，尝试这么多次，还是没有连接上，算了吧");
                    getClientPool().stop();
                    return;
                }
                Logger.getGlobal().warning("连接超时，即将尝试重新连接");
                NettyClient.this.getClientPool().schuild(new Runnable() {
                    @Override
                    public void run() {
                       NettyClient.this.start();
                    }
                }, 2000);
            }
            }
        });
    }

    /*
     * 停止组件
     */
    @Override
    public void stop() {
        super.stop();
        this.setStatus(ClientStatus.Stopped);
        this.channel.close();
    }

    public NioSocketChannel getChannel() {
        return channel;
    }

    public void setGroup(NioEventLoopGroup group) {
        this.group = group;
    }

    public static void main(String args[]) {
        final NettyClientPool pool = new NettyClientPool(2, "127.0.0.1");
        pool.start();

        for (int i = 0; i < 100000000; i++) {
            Request request = new Request("/", Request.RequestMethod.POST);
            request.getBody().writeBytes("樊劲松".getBytes(Charset.forName("utf-8")));
            pool.requestWithTimeOut(request, 2000).addListener(new Future.Listener() {
                @Override
                public void complete(Object arg) {
                    Response response = (Response) arg;
                    System.out.println(response.getBody().toString(Charset.forName("utf-8")));
                    //System.out.println("ok");
                }

                @Override
                public void exception(Throwable t) {
                    System.out.println(t);
                }
            });
            try {
                Thread.currentThread().sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        /*
        Future future = pool.request(new Request());
        future.addListener(new Future.Listener() {
            @Override
            public void complete(Object arg) {
                //pool.stop();
                pool.request(new Request());
            }

            @Override
            public void exception(Throwable t) {
                System.out.println("haha error : " + t);
                //pool.stop();
            }
        });*/
    }



}
