package com.shimizukenta.tmp.secs;

import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;

public class ExampleConnectionListener implements ConnectionEventListener {


    @Override
    public void connectionClosed(ConnectionEvent event) {
            System.out.println("Connection closed+ event: "+event      );
    }

    @Override
    public void connectionErrorOccurred(ConnectionEvent event) {
            System.out.println("Connection error occurred event: "+event);
    }
}
