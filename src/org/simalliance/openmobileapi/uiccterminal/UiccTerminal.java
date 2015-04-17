package org.simalliance.openmobileapi.uiccterminal;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.IccOpenLogicalChannelResponse;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.NoSuchElementException;

import org.simalliance.openmobileapi.service.ITerminalService;
import org.simalliance.openmobileapi.service.SmartcardError;
import org.simalliance.openmobileapi.service.OpenLogicalChannelResponse;

public final class UiccTerminal extends Service {

    private static final String TAG = "UiccTerminal";

    public static final String ACTION_SIM_STATE_CHANGED = "org.simalliance.openmobileapi.action.SIM_STATE_CHANGED";

    private final ITerminalService.Stub mTerminalBinder = new TerminalServiceImplementation();

    private TelephonyManager manager = null;

    private List<Integer> channelIds;

    private BroadcastReceiver mSimReceiver;

    private String currentSelectedFilePath = "";

    private static byte[] mAtr = null;

    @Override
    public IBinder onBind(Intent intent) {
        return mTerminalBinder;
    }

    @Override
    public void onCreate() {
        registerSimStateChangedEvent(this);
        // Constructor
        channelIds = new ArrayList<>();
        // Occupy channelIds[0] to avoid return channel number = 0 on openLogicalChannel
        channelIds.add(0xFFFFFFFF);

        manager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override
    public void onDestroy() {
        unregisterSimStateChangedEvent(getApplicationContext());
        super.onDestroy();
    }

    private byte[] stringToByteArray(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private String byteArrayToString(byte[] b, int start) {
        if (b == null) {
            return "";
        }
        StringBuilder s = new StringBuilder();
        for (int i = start; i < b.length; i++) {
            s.append(Integer.toHexString(0x100 + (b[i] & 0xff)).substring(1));
        }
        return s.toString();
    }

    /**
     * Clear the channel number.
     *
     * @param cla
     *
     * @return the cla without channel number
     */
    private byte clearChannelNumber(byte cla) {
        // bit 7 determines which standard is used
        boolean isFirstInterindustryClassByteCoding = (cla & 0x40) == 0x00;

        if (isFirstInterindustryClassByteCoding) {
            // First Interindustry Class Byte Coding
            // see 11.1.4.1: channel number is encoded in the 2 rightmost bits
            return (byte) (cla & 0xFC);
        } else {
            // Further Interindustry Class Byte Coding
            // see 11.1.4.2: channel number is encoded in the 4 rightmost bits
            return (byte) (cla & 0xF0);
        }
    }

    /**
     * Extracts the channel number from a CLA byte. Specified in GlobalPlatform
     * Card Specification 2.2.0.7: 11.1.4 Class Byte Coding.
     *
     * @param cla
     *            the command's CLA byte
     * @return the channel number within [0x00..0x0F]
     */
    private int parseChannelNumber(byte cla) {
        // bit 7 determines which standard is used
        boolean isFirstInterindustryClassByteCoding = (cla & 0x40) == 0x00;

        if (isFirstInterindustryClassByteCoding) {
            // First Interindustry Class Byte Coding
            // see 11.1.4.1: channel number is encoded in the 2 rightmost bits
            return cla & 0x03;
        } else {
            // Further Interindustry Class Byte Coding
            // see 11.1.4.2: channel number is encoded in the 4 rightmost bits
            return (cla & 0x0F) + 4;
        }
    }

    /**
     * Performs all the logic for opening a logical channel.
     *
     * @param aid The AID to which the channel shall be opened, empty string to
     * specify "no AID".
     *
     * @return The index of the opened channel ID in the channelIds list.
     */
    private OpenLogicalChannelResponse iccOpenLogicalChannel(String aid)
            throws  NoSuchElementException, MissingResourceException {
        Log.d(TAG, "iccOpenLogicalChannel > " + aid);
        // Remove any previously stored selection response
        IccOpenLogicalChannelResponse response = manager.iccOpenLogicalChannel(aid);
        int status = response.getStatus();
        if (status != IccOpenLogicalChannelResponse.STATUS_NO_ERROR) {
            Log.d(TAG, "iccOpenLogicalChannel failed.");
            // An error occured.
            if (status == IccOpenLogicalChannelResponse.STATUS_MISSING_RESOURCE) {
                error.setError(MissingResourceException.class, "all channels are used");
                return null;
            }
            if (status == IccOpenLogicalChannelResponse.STATUS_NO_SUCH_ELEMENT) {
                error.setError(NoSuchElementException.class, "applet not found");
                return null;
            }
            error.setError(RuntimeException.class, "open channel failed");
            return null;
        }
        // Operation succeeded
        // Set the select response
        Log.d(TAG, "iccOpenLogicalChannel < " + byteArrayToString(response.getSelectResponse(), 0));
        // Save channel ID. First check if there is any channelID which is empty
        // to reuse it.
        for (int i = 1; i < channelIds.size(); i++) {
            if (channelIds.get(i) == 0) {
                channelIds.set(i, response.getChannel());
                return new org.simalliance.openmobileapi.service.OpenLogicalChannelResponse(i, response.getSelectResponse());
            }
        }
        // If no channel ID is empty, append one at the end of the list.
        channelIds.add(response.getChannel());
        return new org.simalliance.openmobileapi.service.OpenLogicalChannelResponse(channelIds.size() - 1, response.getSelectResponse());
    }

    public static String getType() {
        return "SIM";
    }

    private void registerSimStateChangedEvent(Context context) {
        Log.v(TAG, "register to android.intent.action.SIM_STATE_CHANGED event");

        IntentFilter intentFilter = new IntentFilter(
                "android.intent.action.SIM_STATE_CHANGED");
        mSimReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("android.intent.action.SIM_STATE_CHANGED".equals(intent.getAction())) {
                    final Bundle extras = intent.getExtras();
                    final boolean simReady = (extras != null)
                            && "READY".equals(extras.getString("ss"));
                    final boolean simLoaded = (extras != null)
                            && "LOADED".equals(extras.getString("ss"));
                    if (simReady || simLoaded) {
                        Log.i(TAG, "SIM is ready or loaded. Checking access rules for"
                                + " updates.");
                        Intent i = new Intent(SIM_STATE_CHANGE_ACTION);
                        sendBroadcast(i);
                    }
                }
            }
        };
        context.registerReceiver(mSimReceiver, intentFilter);
    }

    private void unregisterSimStateChangedEvent(Context context) {
        if (mSimReceiver != null) {
            Log.v(TAG, "unregister SIM_STATE_CHANGED event");
            context.unregisterReceiver(mSimReceiver);
            mSimReceiver = null;
        }
    }

    /**
     * The Terminal service interface implementation.
     */
    final class TerminalServiceImplementation extends ITerminalService.Stub {

        @Override
        public String getType() {
            return UiccTerminal.getType();
        }

        @Override
        public org.simalliance.openmobileapi.service.OpenLogicalChannelResponse internalOpenLogicalChannel(byte[] aid, org.simalliance.openmobileapi.service.SmartcardError error) throws RemoteException {
            String aidString;
            if (aid == null) {
                aidString = "";
            } else {
                aidString = byteArrayToString(aid, 0);
            }
            return iccOpenLogicalChannel(aidString, error);
        }

        @Override
        public void internalCloseLogicalChannel(int channelNumber, org.simalliance.openmobileapi.service.SmartcardError error)
                throws RemoteException {
            if (channelNumber == 0) {
                return;
            }
            if (channelIds.get(channelNumber) == 0) {
                error.setError(RemoteException.class, "channel not open");
                return;
            }
            try {
                if (!manager.iccCloseLogicalChannel(channelIds.get(channelNumber))) {
                    error.setError(RemoteException.class, "close channel failed");
                    return;
                }
            } catch (Exception ex) {
                Log.e(TAG, "Error while closing the logical channel", ex);
                error.setError(RemoteException.class, "close channel failed");
                return;
            }
            channelIds.set(channelNumber, 0);
        }

        @Override
        public byte[] internalTransmit(byte[] command, org.simalliance.openmobileapi.service.SmartcardError error) throws RemoteException {
            Log.d(TAG, "internalTransmit > " + byteArrayToString(command, 0));
            int cla = clearChannelNumber(command[0]) & 0xff;
            int ins = command[1] & 0xff;
            int p1 = command[2] & 0xff;
            int p2 = command[3] & 0xff;
            int p3 = -1;
            if (command.length > 4) {
                p3 = command[4] & 0xff;
            }
            String data = null;
            if (command.length > 5) {
                data = byteArrayToString(command, 5);
            }

            int channelNumber = parseChannelNumber(command[0]);

            String response= "";
            if (channelNumber == 0) {
                try {
                    response = manager.iccTransmitApduBasicChannel(
                            cla, ins, p1, p2, p3, data);
                } catch (Exception ex) {
                    Log.e(TAG, "Error while transmitting APDU on basic chanel", ex);
                    error.setError(RemoteException.class, "transmit command failed");
                    return new byte[0];
                }
            } else {
                if ((channelNumber > 0) && (channelIds.get(channelNumber) == 0)) {
                    error.setError(RemoteException.class, "channel not open");
                    return new byte[0];
                }

                try {
                    response = manager.iccTransmitApduLogicalChannel(
                            channelIds.get(channelNumber), cla, ins, p1, p2, p3, data);
                } catch (Exception ex) {
                    Log.e(TAG, "Error while transmitting apdu on logical channel", ex);
                    error.setError(RemoteException.class, "transmit command failed");
                    return new byte[0];
                }
            }
            Log.d(TAG, "internalTransmit < " + response);
            return stringToByteArray(response);
        }

        @Override
        public byte[] getAtr() {
            if (mAtr == null) {
                String atr = manager.iccGetAtr();
                Log.d(TAG, "atr = " + atr == null ? "" : atr);
                if (atr != null && !"".equals(atr)) {
                    mAtr = stringToByteArray(atr);
                }
            }
            return mAtr;
        }

        @Override
        public boolean isCardPresent() throws RemoteException {
            if (manager == null) {
                return false;
            }
            String simState = SystemProperties
                    .get(TelephonyProperties.PROPERTY_SIM_STATE);

            Log.d(TAG, "SIMSTATE" + simState + manager.hasIccCard() + manager.getSimState());
            return "READY".equals(simState);
        }

        @Override
        public byte[] simIOExchange(int fileID, String filePath, byte[] cmd, org.simalliance.openmobileapi.service.SmartcardError error)
                throws RemoteException {
            int ins = 0;
            int p1 = cmd[2] & 0xff;
            int p2 = cmd[3] & 0xff;
            int p3 = cmd[4] & 0xff;
            switch(cmd[1]) {
                case (byte) 0xB0:
                    ins = 176;
                    break;
                case (byte) 0xB2:
                    ins = 178;
                    break;
                case (byte) 0xA4:
                    ins = 192;
                    p1 = 0;
                    p2 = 0;
                    p3 = 15;
                    break;
                default:
                    error.setError(IOException.class, "Unknown SIM_IO command");
                    throw new RemoteException();
            }

            if (filePath != null && filePath.length() > 0) {
                currentSelectedFilePath = filePath;
            }

            return manager.iccExchangeSimIO(
                    fileID, ins, p1, p2, p3, currentSelectedFilePath);
        }

        @Override
        public String getSEChangeAction() {
            return SIM_STATE_CHANGE_ACTION;
        }
    }
}
