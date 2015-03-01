/*
 * Copyright 2014 Saikrishna Arcot <saiarcot895@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License or (at your option) version 3 or any later version
 * accepted by the membership of KDE e.V. (or its successor approved
 * by the membership of KDE e.V.), which shall act as a proxy
 * defined in Section 14 of version 3 of the license.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
*/

package org.kde.kdeconnect.Backends.BluetoothBackend;

import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothServerSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Build;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.util.Log;

import org.kde.kdeconnect.Backends.BaseLinkProvider;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;
import org.kde.kdeconnect.UserInterface.CustomDevicesActivity;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

public class BluetoothLinkProvider extends BaseLinkProvider {

    private static final UUID SERVICE_UUID = UUID.fromString("576bf9a0-98c9-11e4-bc89-0002a5d5c51b");
    private static final int REQUEST_ENABLE_BT = 48;

    private final Context context;
    private final Map<String, BluetoothLink> visibleComputers = new HashMap<>();
    private final Map<BluetoothDevice, BluetoothSocket> sockets = new HashMap<>();

    private BluetoothAdapter bluetoothAdapter = null;

    private ServerRunnable serverRunnable;
    private ClientRunnable clientRunnable;

    private void addLink(NetworkPackage identityPackage, BluetoothLink link) {
        String deviceId = identityPackage.getString("deviceId");
        Log.i("BluetoothLinkProvider","addLink to "+deviceId);
        BluetoothLink oldLink = visibleComputers.get(deviceId);
        if (oldLink == link) {
            Log.e("KDEConnect", "BluetoothLinkProvider: oldLink == link. This should not happen!");
            return;
        }
        visibleComputers.put(deviceId, link);
        connectionAccepted(identityPackage, link);
        if (oldLink != null) {
            Log.i("BluetoothLinkProvider","Removing old connection to same device");
            oldLink.disconnect();
        }
    }

    public BluetoothLinkProvider(Context context) {
        this.context = context;

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e("BluetoothLinkProvider","No bluetooth adapter found.");
        }
    }

    @Override
    public void onStart() {
        if (bluetoothAdapter == null) {
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            Log.e("BluetoothLinkProvider","Bluetooth adapter not enabled.");
            // TODO: next line needs to be called from an existing activity, so move it?
            // startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            // TODO: Check result of the previous command, whether the user allowed bluetooth or not.
            return;
        }

        //This handles the case when I'm the existing device in the network and receive a hello package
        clientRunnable = new ClientRunnable();
        new Thread(clientRunnable).start();

        // I'm on a new network, let's be polite and introduce myself
        serverRunnable = new ServerRunnable();
        new Thread(serverRunnable).start();
    }

    @Override
    public void onNetworkChange() {
        onStop();
        onStart();
    }

    @Override
    public void onStop() {
        if (bluetoothAdapter == null || clientRunnable == null || serverRunnable == null) {
            return;
        }

        clientRunnable.stopProcessing();
        serverRunnable.stopProcessing();
    }

    @Override
    public String getName() {
        return "BluetoothLinkProvider";
    }

    public void disconnectedLink(BluetoothLink link, String deviceId, BluetoothSocket socket) {
        sockets.remove(socket.getRemoteDevice());
        visibleComputers.remove(deviceId);
        connectionLost(link);
    }

    private class ServerRunnable implements Runnable {

        private boolean continueProcessing = true;
        private BluetoothServerSocket serverSocket;

        public void stopProcessing() {
            continueProcessing = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void run() {
            try {
                serverSocket = bluetoothAdapter
                        .listenUsingRfcommWithServiceRecord("KDE Connect", SERVICE_UUID);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            if (continueProcessing) {
                try {
                    BluetoothSocket socket = serverSocket.accept();
                    connect(socket);
                } catch (IOException ignored) {
                }
            }
        }

        private void connect(BluetoothSocket socket) throws IOException {
            //socket.connect();
            OutputStream outputStream = socket.getOutputStream();
            if (sockets.containsKey(socket.getRemoteDevice())) {
                Log.i("BTLinkProvider/Server", "Received duplicate connection from " + socket.getRemoteDevice().getAddress());
                socket.close();
                return;
            } else {
                sockets.put(socket.getRemoteDevice(), socket);
            }

            Log.i("BTLinkProvider/Server", "Received connection from " + socket.getRemoteDevice().getAddress());

            NetworkPackage np = NetworkPackage.createIdentityPackage(context);
            byte[] message = np.serialize().getBytes("UTF-8");
            outputStream.write(message);

            Log.i("BTLinkProvider/Server", "Sent identity package");

            // Listen for the response
            int character;
            StringBuilder sb = new StringBuilder();
            while(sb.lastIndexOf("\n") == -1 && (character = socket.getInputStream().read()) != -1) {
                sb.append((char)character);
            }

            String response = sb.toString();
            final NetworkPackage identityPackage = NetworkPackage.unserialize(response);

            if (!identityPackage.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
                Log.e("BTLinkProvider/Server", "2 Expecting an identity package");
                return;
            }

            Log.i("BTLinkProvider/Server", "Received identity package");

            BluetoothLink link = new BluetoothLink(socket,
                    identityPackage.getString("deviceId"), BluetoothLinkProvider.this);

            addLink(identityPackage, link);
        }
    }

    private class ClientRunnable extends BroadcastReceiver implements Runnable {

        private boolean continueProcessing = true;
        private Map<BluetoothDevice, Thread> connectionThreads = new HashMap<>();

        public void stopProcessing() {
            continueProcessing = false;
        }

        @Override
        public void run() {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_UUID);
            context.registerReceiver(this, filter);

            while (continueProcessing) {
                connectToDevices();
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ignored) {
                }
            }

            context.unregisterReceiver(this);
        }

        private void connectToDevices() {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            Log.i("BluetoothLinkProvider", "Bluetooth adapter paired devices: " + pairedDevices.size());
            if (pairedDevices.size() > 0) {
                // Loop through paired devices
                for (BluetoothDevice device : pairedDevices) {
                    if (sockets.containsKey(device)) {
                        continue;
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                        device.fetchUuidsWithSdp();
                    } else {
                        connectToDevice(device);
                    }
                }
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(BluetoothDevice.ACTION_UUID)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                Parcelable[] activeUuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);

                if (sockets.containsKey(device)) {
                    return;
                }

                if (activeUuids == null) {
                    return;
                }

                for (Parcelable uuid: activeUuids) {
                    if (uuid.toString().equals(SERVICE_UUID.toString())) {
                        connectToDevice(device);
                        return;
                    }
                }
            }
        }

        private void connectToDevice(BluetoothDevice device) {
            if (!connectionThreads.containsKey(device) || !connectionThreads.get(device).isAlive()) {
                Thread connectionThread = new Thread(new ClientConnect(device));
                connectionThread.start();
                connectionThreads.put(device, connectionThread);
            }
        }


    }

    private class ClientConnect implements Runnable {

        private final BluetoothDevice device;

        public ClientConnect(BluetoothDevice device) {
            this.device = device;
        }

        @Override
        public void run() {
            connectToDevice();
        }

        private void connectToDevice() {
            BluetoothSocket socket;
            try {
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                socket.connect();
                sockets.put(device, socket);
            } catch (IOException e) {
                e.printStackTrace();
                Log.e("BTLinkProvider/Client", "Could not connect to KDE Connect service on " + device.getAddress());
                return;
            }

            Log.i("BTLinkProvider/Client", "Connected to " + device.getAddress());

            try {
                int character;
                StringBuilder sb = new StringBuilder();
                while(sb.lastIndexOf("\n") == -1 && (character = socket.getInputStream().read()) != -1) {
                    sb.append((char)character);
                }

                String message = sb.toString();
                final NetworkPackage identityPackage = NetworkPackage.unserialize(message);

                if (!identityPackage.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
                    Log.e("BTLinkProvider/Client", "1 Expecting an identity package");
                    socket.close();
                    return;
                }

                Log.i("BTLinkProvider/Client", "Received identity package");

                String myId = NetworkPackage.createIdentityPackage(context).getString("deviceId");
                if (identityPackage.getString("deviceId").equals(myId)) {
                    // Probably won't happen, but just to be safe
                    socket.close();
                    return;
                }

                if (visibleComputers.containsKey(identityPackage.getString("deviceId"))) {
                    return;
                }

                Log.i("BTLinkProvider/Client", "Identity package received, creating link");

                final BluetoothLink link = new BluetoothLink(socket,
                        identityPackage.getString("deviceId"), BluetoothLinkProvider.this);

                NetworkPackage np2 = NetworkPackage.createIdentityPackage(context);
                link.sendPackage(np2,new Device.SendPackageStatusCallback() {
                    @Override
                    protected void onSuccess() {
                        addLink(identityPackage, link);
                    }

                    @Override
                    protected void onFailure(Throwable e) {

                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
                Log.e("BTLinkProvider/Client", "Connection lost/disconnected on " + device.getAddress());
            }
        }
    }
}
