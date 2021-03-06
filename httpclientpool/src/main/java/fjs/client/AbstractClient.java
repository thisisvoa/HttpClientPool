package fjs.client;

import fjs.clientpool.ClientPool;
import fjs.http.Request;

import java.util.logging.Logger;

/**
 * Created by fjs on 14-6-20.
 */
public abstract class AbstractClient implements Client{
    private String remoteHost;
    private ClientPool pool;
    private volatile Request request;
    private volatile ClientStatus status;

    public AbstractClient(ClientPool pool) {
        this.pool = pool;
        this.remoteHost = pool.getRemoteHost();
        this.status = ClientStatus.Startting;
    }


    @Override
    public void setRequest(Request request) {
        this.request = request;
    }

    @Override
    public Request getRequest() {
        return this.request;
    }

    /**
     * 获取所属的pool
     *
     * @return
     */
    @Override
    public ClientPool getClientPool() {
        return this.pool;
    }

    /**
     * 获取远程地址
     *
     * @return
     */
    @Override
    public String getRemoteHost() {
        return this.remoteHost;
    }

    /**
     * 取消正在运行的request
     */
    @Override
    public void cancel() {
        if (this.request != null) {

        }
    }




    /**
     * 停止组件，这里主要是通知pool有client退出了
     */
    @Override
    public void stop() {
        this.getClientPool().clientExit(this);
    }


    /**
     * 用于返回当前client的状态
     *
     * @return
     */
    @Override
    public ClientStatus getStatus() {
        return this.status;
    }

    /**
     *  用于设置当前client的状态
     *
     * @param status
     */
    @Override
    public void setStatus(ClientStatus status) {
        this.status = status;
    }

    /**
     * 当当前client可以用的时候调用的方法，将这个client加入到pool啥的
     */
    @Override
    public synchronized void ready() {
       if (this.status.equals(ClientStatus.Working) || status.equals(ClientStatus.Startting)) {
           this.setStatus(ClientStatus.Ready);
           this.getClientPool().addClient(this);
           this.setRequest(null);
       } else {
           Logger.getGlobal().warning("client准备好了，但是client的状态不对啊");
           this.start();
       }
    }
}
