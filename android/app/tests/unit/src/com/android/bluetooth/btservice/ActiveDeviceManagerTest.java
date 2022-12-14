/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.btservice;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.le_audio.LeAudioService;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class ActiveDeviceManagerTest {
    private BluetoothAdapter mAdapter;
    private Context mContext;
    private BluetoothDevice mA2dpDevice;
    private BluetoothDevice mHeadsetDevice;
    private BluetoothDevice mA2dpHeadsetDevice;
    private BluetoothDevice mHearingAidDevice;
    private BluetoothDevice mLeAudioDevice;
    private BluetoothDevice mLeHearingAidDevice;
    private BluetoothDevice mSecondaryAudioDevice;
    private ArrayList<BluetoothDevice> mDeviceConnectionStack;
    private BluetoothDevice mMostRecentDevice;
    private ActiveDeviceManager mActiveDeviceManager;
    private static final int TIMEOUT_MS = 1000;

    @Mock private AdapterService mAdapterService;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private A2dpService mA2dpService;
    @Mock private HeadsetService mHeadsetService;
    @Mock private HearingAidService mHearingAidService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private AudioManager mAudioManager;
    @Mock private DatabaseManager mDatabaseManager;
    private AudioManager.OnModeChangedListener mAudioModeChangedListener;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        Assume.assumeTrue("Ignore test when A2dpService is not enabled", A2dpService.isEnabled());
        Assume.assumeTrue("Ignore test when HeadsetService is not enabled",
                HeadsetService.isEnabled());

        // Set up mocks and test assets
        MockitoAnnotations.initMocks(this);
        TestUtils.setAdapterService(mAdapterService);

        doAnswer(invocation -> {
            mAudioModeChangedListener = invocation.getArgument(1);
            return null;
        }).when(mAudioManager).addOnModeChangedListener(any(), any());
        when(mAdapterService.getSystemService(Context.AUDIO_SERVICE)).thenReturn(mAudioManager);
        when(mAdapterService.getSystemServiceName(AudioManager.class))
                .thenReturn(Context.AUDIO_SERVICE);
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mServiceFactory.getA2dpService()).thenReturn(mA2dpService);
        when(mServiceFactory.getHeadsetService()).thenReturn(mHeadsetService);
        when(mServiceFactory.getHearingAidService()).thenReturn(mHearingAidService);
        when(mServiceFactory.getLeAudioService()).thenReturn(mLeAudioService);

        mActiveDeviceManager = new ActiveDeviceManager(mAdapterService, mServiceFactory);
        mActiveDeviceManager.start();
        mAdapter = BluetoothAdapter.getDefaultAdapter();

        // Get devices for testing
        mA2dpDevice = TestUtils.getTestDevice(mAdapter, 0);
        mHeadsetDevice = TestUtils.getTestDevice(mAdapter, 1);
        mA2dpHeadsetDevice = TestUtils.getTestDevice(mAdapter, 2);
        mHearingAidDevice = TestUtils.getTestDevice(mAdapter, 3);
        mLeAudioDevice = TestUtils.getTestDevice(mAdapter, 4);
        mLeHearingAidDevice = TestUtils.getTestDevice(mAdapter, 5);
        mSecondaryAudioDevice = TestUtils.getTestDevice(mAdapter, 6);
        mDeviceConnectionStack = new ArrayList<>();
        mMostRecentDevice = null;

        when(mA2dpService.setActiveDevice(any(), anyBoolean())).thenReturn(true);
        when(mHeadsetService.setActiveDevice(any())).thenReturn(true);
        when(mHearingAidService.setActiveDevice(any(), anyBoolean())).thenReturn(true);
        when(mLeAudioService.setActiveDevice(any(), anyBoolean())).thenReturn(true);

        when(mA2dpService.getFallbackDevice()).thenAnswer(invocation -> {
            int index = Math.max(mDeviceConnectionStack.indexOf(mA2dpDevice),
                    mDeviceConnectionStack.indexOf(mA2dpHeadsetDevice));
            return (index == -1) ? null : mDeviceConnectionStack.get(index);
        });
        when(mHeadsetService.getFallbackDevice()).thenAnswer(invocation -> {
            int index = Math.max(mDeviceConnectionStack.indexOf(mHeadsetDevice),
                    mDeviceConnectionStack.indexOf(mA2dpHeadsetDevice));
            return (index == -1) ? null : mDeviceConnectionStack.get(index);
        });
        when(mDatabaseManager.getMostRecentlyConnectedDevicesInList(any())).thenAnswer(
                invocation -> {
                    List<BluetoothDevice> devices = invocation.getArgument(0);
                    if (devices == null || devices.size() == 0) {
                        return null;
                    } else if (devices.contains(mLeHearingAidDevice)) {
                        return mLeHearingAidDevice;
                    } else if (devices.contains(mHearingAidDevice)) {
                        return mHearingAidDevice;
                    } else if (mMostRecentDevice != null && devices.contains(mMostRecentDevice)) {
                        return mMostRecentDevice;
                    } else {
                        return devices.get(0);
                    }
                }
        );
    }

    @After
    public void tearDown() throws Exception {
        if (!HeadsetService.isEnabled() || !A2dpService.isEnabled()) {
            return;
        }
        mActiveDeviceManager.cleanup();
        TestUtils.clearAdapterService(mAdapterService);
    }

    @Test
    public void testSetUpAndTearDown() {}

    /**
     * One A2DP is connected.
     */
    @Test
    public void onlyA2dpConnected_setA2dpActive() {
        a2dpConnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);
    }

    /**
     * Two A2DP are connected. Should set the second one active.
     */
    @Test
    public void secondA2dpConnected_setSecondA2dpActive() {
        a2dpConnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);

        a2dpConnected(mA2dpHeadsetDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice, false);
    }

    /**
     * One A2DP is connected and disconnected later. Should then set active device to null.
     */
    @Test
    public void lastA2dpDisconnected_clearA2dpActive() {
        a2dpConnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);

        a2dpDisconnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(null, false);
    }

    /**
     * Two A2DP are connected and active device is explicitly set.
     */
    @Test
    public void a2dpActiveDeviceSelected_setActive() {
        a2dpConnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);

        a2dpConnected(mA2dpHeadsetDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice, false);

        a2dpActiveDeviceChanged(mA2dpDevice);
        // Don't call mA2dpService.setActiveDevice()
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService).setActiveDevice(mA2dpDevice, false);
        Assert.assertEquals(mA2dpDevice, mActiveDeviceManager.getA2dpActiveDevice());
    }

    /**
     * Two A2DP devices are connected and the current active is then disconnected.
     * Should then set active device to fallback device.
     */
    @Test
    public void a2dpSecondDeviceDisconnected_fallbackDeviceActive() {
        a2dpConnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);

        a2dpConnected(mSecondaryAudioDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice, false);

        Mockito.clearInvocations(mA2dpService);
        a2dpDisconnected(mSecondaryAudioDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);
    }

    /**
     * One Headset is connected.
     */
    @Test
    public void onlyHeadsetConnected_setHeadsetActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        headsetConnected(mHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);
    }

    /**
     * Two Headset are connected. Should set the second one active.
     */
    @Test
    public void secondHeadsetConnected_setSecondHeadsetActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        headsetConnected(mHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        headsetConnected(mA2dpHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * One Headset is connected and disconnected later. Should then set active device to null.
     */
    @Test
    public void lastHeadsetDisconnected_clearHeadsetActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        headsetConnected(mHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        headsetDisconnected(mHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(isNull());
    }

    /**
     * Two Headset are connected and active device is explicitly set.
     */
    @Test
    public void headsetActiveDeviceSelected_setActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        headsetConnected(mHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        headsetConnected(mA2dpHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice);

        headsetActiveDeviceChanged(mHeadsetDevice);
        // Don't call mHeadsetService.setActiveDevice()
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
        Assert.assertEquals(mHeadsetDevice, mActiveDeviceManager.getHfpActiveDevice());
    }

    /**
     * Two Headsets are connected and the current active is then disconnected.
     * Should then set active device to fallback device.
     */
    @Test
    public void headsetSecondDeviceDisconnected_fallbackDeviceActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        headsetConnected(mHeadsetDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        headsetConnected(mSecondaryAudioDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice);

        Mockito.clearInvocations(mHeadsetService);
        headsetDisconnected(mSecondaryAudioDevice);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);
    }

    /**
     * Test setActiveDevice(null) for both A2dpService and HeadsetService are called when an
     * activated combo (A2DP + Headset) device is disconnected while in call.
     */
    @Test
    public void a2dpHeadsetDisconnected_callsSetActiveDeviceNull() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        // A2dpHeadset connected
        headsetConnected(mA2dpHeadsetDevice);
        a2dpConnected(mA2dpHeadsetDevice);
        // Verify activation of A2DP in media mode
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice, false);

        // Mode changed to call mode
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);
        mAudioModeChangedListener.onModeChanged(AudioManager.MODE_IN_CALL);
        // Verify activation of HFP in call mode
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice);

        // A2dpHeadset disconnected
        headsetDisconnected(mA2dpHeadsetDevice);
        a2dpDisconnected(mA2dpHeadsetDevice);
        // Verify setActiveDevice(null) called
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(null);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(null, false);
    }

    /**
     * A combo (A2DP + Headset) device is connected. Then a Hearing Aid is connected.
     */
    @Test
    public void hearingAidActive_clearA2dpAndHeadsetActive() {
        Assume.assumeTrue("Ignore test when HearingAidService is not enabled",
                HearingAidService.isEnabled());

        a2dpConnected(mA2dpHeadsetDevice);
        headsetConnected(mA2dpHeadsetDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice);

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(null, true);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(isNull());
    }

    /**
     * A Hearing Aid is connected. Then a combo (A2DP + Headset) device is connected.
     */
    @Test
    public void hearingAidActive_dontSetA2dpAndHeadsetActive() {
        Assume.assumeTrue("Ignore test when HearingAidService is not enabled",
                HearingAidService.isEnabled());

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        a2dpConnected(mA2dpHeadsetDevice);
        headsetConnected(mA2dpHeadsetDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService, never()).setActiveDevice(mA2dpHeadsetDevice, false);
        verify(mHeadsetService, never()).setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * A Hearing Aid is connected. Then an A2DP active device is explicitly set.
     */
    @Test
    public void hearingAidActive_setA2dpActiveExplicitly() {
        Assume.assumeTrue("Ignore test when HearingAidService is not enabled",
                HearingAidService.isEnabled());

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        a2dpConnected(mA2dpHeadsetDevice);
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mHearingAidService).setActiveDevice(null, true);
        // Don't call mA2dpService.setActiveDevice()
        verify(mA2dpService, never()).setActiveDevice(mA2dpHeadsetDevice, false);
        Assert.assertEquals(mA2dpHeadsetDevice, mActiveDeviceManager.getA2dpActiveDevice());
        Assert.assertEquals(null, mActiveDeviceManager.getHearingAidActiveDevice());
    }

    /**
     * A Hearing Aid is connected. Then a Headset active device is explicitly set.
     */
    @Test
    public void hearingAidActive_setHeadsetActiveExplicitly() {
        Assume.assumeTrue("Ignore test when HearingAidService is not enabled",
                HearingAidService.isEnabled());

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        headsetConnected(mA2dpHeadsetDevice);
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mHearingAidService).setActiveDevice(null, true);
        // Don't call mHeadsetService.setActiveDevice()
        verify(mHeadsetService, never()).setActiveDevice(mA2dpHeadsetDevice);
        Assert.assertEquals(mA2dpHeadsetDevice, mActiveDeviceManager.getHfpActiveDevice());
        Assert.assertEquals(null, mActiveDeviceManager.getHearingAidActiveDevice());
    }

    /**
     * One LE Audio is connected.
     */
    @Test
    public void onlyLeAudioConnected_setHeadsetActive() {
        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);
    }

    /**
     * Two LE Audio are connected. Should set the second one active.
     */
    @Test
    public void secondLeAudioConnected_setSecondLeAudioActive() {
        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);

        leAudioConnected(mSecondaryAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice, false);
    }

    /**
     * One LE Audio  is connected and disconnected later. Should then set active device to null.
     */
    @Test
    public void lastLeAudioDisconnected_clearLeAudioActive() {
        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);

        leAudioDisconnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(null, false);
    }

    /**
     * Two LE Audio are connected and active device is explicitly set.
     */
    @Test
    public void leAudioActiveDeviceSelected_setActive() {
        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);

        leAudioConnected(mSecondaryAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice, false);

        leAudioActiveDeviceChanged(mLeAudioDevice);
        // Don't call mLeAudioService.setActiveDevice()
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice, false);
        Assert.assertEquals(mLeAudioDevice, mActiveDeviceManager.getLeAudioActiveDevice());
    }

    /**
     * Two LE Audio are connected and the current active is then disconnected.
     * Should then set active device to fallback device.
     */
    @Test
    public void leAudioSecondDeviceDisconnected_fallbackDeviceActive() {
        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);

        leAudioConnected(mSecondaryAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mSecondaryAudioDevice, false);

        Mockito.clearInvocations(mLeAudioService);
        leAudioDisconnected(mSecondaryAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);
    }

    /**
     * A combo (A2DP + Headset) device is connected. Then an LE Audio is connected.
     */
    @Test
    public void leAudioActive_clearA2dpAndHeadsetActive() {
        Assume.assumeTrue("Ignore test when LeAudioService is not enabled",
                LeAudioService.isEnabled());

        a2dpConnected(mA2dpHeadsetDevice);
        headsetConnected(mA2dpHeadsetDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpHeadsetDevice);

        leAudioActiveDeviceChanged(mLeAudioDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(null, true);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(isNull());
    }

    /**
     * An LE Audio is connected. Then a combo (A2DP + Headset) device is connected.
     */
    @Test
    public void leAudioActive_dontSetA2dpAndHeadsetActive() {
        Assume.assumeTrue("Ignore test when LeAudioService is not enabled",
                LeAudioService.isEnabled());

        leAudioActiveDeviceChanged(mLeAudioDevice);
        a2dpConnected(mA2dpHeadsetDevice);
        headsetConnected(mA2dpHeadsetDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice, false);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * An LE Audio is connected. Then an A2DP active device is explicitly set.
     */
    @Test
    public void leAudioActive_setA2dpActiveExplicitly() {
        Assume.assumeTrue("Ignore test when LeAudioService is not enabled",
                LeAudioService.isEnabled());

        leAudioActiveDeviceChanged(mLeAudioDevice);
        a2dpConnected(mA2dpHeadsetDevice);
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService).setActiveDevice(null, true);
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice, false);
        Assert.assertEquals(mA2dpHeadsetDevice, mActiveDeviceManager.getA2dpActiveDevice());
        Assert.assertEquals(null, mActiveDeviceManager.getLeAudioActiveDevice());
    }

    /**
     * An LE Audio is connected. Then a Headset active device is explicitly set.
     */
    @Test
    public void leAudioActive_setHeadsetActiveExplicitly() {
        Assume.assumeTrue("Ignore test when LeAudioService is not enabled",
                LeAudioService.isEnabled());
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);
        leAudioActiveDeviceChanged(mLeAudioDevice);
        headsetConnected(mA2dpHeadsetDevice);
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService).setActiveDevice(null, false);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);
        Assert.assertEquals(mA2dpHeadsetDevice, mActiveDeviceManager.getHfpActiveDevice());
        Assert.assertEquals(null, mActiveDeviceManager.getLeAudioActiveDevice());
    }

    /**
     * An LE Audio connected. An A2DP connected. The A2DP disconnected.
     * Then the LE Audio should be the active one.
     */
    @Test
    public void leAudioAndA2dpConnectedThenA2dpDisconnected_fallbackToLeAudio() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);

        a2dpConnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);

        Mockito.clearInvocations(mLeAudioService);
        a2dpDisconnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS).atLeast(1)).setActiveDevice(null, true);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);
    }

    /**
     * An A2DP connected. An LE Audio connected. The LE Audio disconnected.
     * Then the A2DP should be the active one.
     */
    @Test
    public void a2dpAndLeAudioConnectedThenLeAudioDisconnected_fallbackToA2dp() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        a2dpConnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);

        leAudioConnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);

        Mockito.clearInvocations(mA2dpService);
        leAudioDisconnected(mLeAudioDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS).atLeast(1)).setActiveDevice(null, true);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);
    }

    /**
     * Two Hearing Aid are connected and the current active is then disconnected.
     * Should then set active device to fallback device.
     */
    @Test
    public void hearingAidSecondDeviceDisconnected_fallbackDeviceActive() {
        hearingAidConnected(mHearingAidDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice, false);

        hearingAidConnected(mSecondaryAudioDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS))
                .setActiveDevice(mSecondaryAudioDevice, false);

        Mockito.clearInvocations(mHearingAidService);
        hearingAidDisconnected(mSecondaryAudioDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice, false);
    }

    /**
     * Hearing aid is connected, but active device is different BT.
     * When the active device is disconnected, the hearing aid should be the active one.
     */
    @Test
    public void activeDeviceDisconnected_fallbackToHearingAid() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        hearingAidConnected(mHearingAidDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice, false);

        leAudioConnected(mLeAudioDevice);
        a2dpConnected(mA2dpDevice);

        a2dpActiveDeviceChanged(mA2dpDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());

        verify(mHearingAidService).setActiveDevice(null, true);
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
        verify(mA2dpService, never()).setActiveDevice(mA2dpDevice, false);

        a2dpDisconnected(mA2dpDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS).atLeast(1)).setActiveDevice(null, true);
        verify(mHearingAidService, timeout(TIMEOUT_MS).times(2))
                .setActiveDevice(mHearingAidDevice, false);
    }

    /**
     * One LE Hearing Aid is connected.
     */
    @Test
    public void onlyLeHearingAidConnected_setLeAudioActive() {
        leHearingAidConnected(mLeHearingAidDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService, never()).setActiveDevice(mLeHearingAidDevice);

        leAudioConnected(mLeHearingAidDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeHearingAidDevice, false);
    }

    /**
     * LE audio is connected after LE Hearing Aid device.
     * Keep LE hearing Aid active.
     */
    @Test
    public void leAudioConnectedAfterLeHearingAid_setLeAudioActiveShouldNotBeCalled() {
        leHearingAidConnected(mLeHearingAidDevice);
        leAudioConnected(mLeHearingAidDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeHearingAidDevice, false);

        leAudioConnected(mLeAudioDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
    }

    /**
     * Test connect/disconnect of devices.
     * Hearing Aid, LE Hearing Aid, A2DP connected, then LE hearing Aid and hearing aid
     * disconnected.
     */
    @Test
    public void activeDeviceChange_withHearingAidLeHearingAidAndA2dpDevices() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        hearingAidConnected(mHearingAidDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice, false);

        leHearingAidConnected(mLeHearingAidDevice);
        leAudioConnected(mLeHearingAidDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeHearingAidDevice, false);

        a2dpConnected(mA2dpDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService, never()).setActiveDevice(mA2dpDevice, false);

        Mockito.clearInvocations(mHearingAidService, mA2dpService);
        leHearingAidDisconnected(mLeHearingAidDevice);
        leAudioDisconnected(mLeHearingAidDevice);
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(mHearingAidDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(null, true);

        hearingAidDisconnected(mHearingAidDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);
    }

    /**
     * A wired audio device is connected. Then all active devices are set to null.
     */
    @Test
    public void wiredAudioDeviceConnected_setAllActiveDevicesNull() {
        a2dpConnected(mA2dpDevice);
        headsetConnected(mHeadsetDevice);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        mActiveDeviceManager.wiredAudioDeviceConnected();
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(null, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(isNull());
        verify(mHearingAidService, timeout(TIMEOUT_MS)).setActiveDevice(null, false);
    }

    @Test
    public void leAudioActive_whileMediaPlayback_useLeAudioForMediaAndHeadsetForCall() {
        leAudioConnected(mLeAudioDevice);
        a2dpConnected(mA2dpDevice);
        headsetConnected(mHeadsetDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        Mockito.clearInvocations(mLeAudioService, mA2dpService, mHeadsetService);
        leAudioActiveDeviceChanged(mLeAudioDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService).setActiveDevice(null, true);
        verify(mHeadsetService).setActiveDevice(null);

        Mockito.clearInvocations(mLeAudioService, mA2dpService, mHeadsetService);
        mAudioModeChangedListener.onModeChanged(AudioManager.MODE_IN_CALL);
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
        verify(mLeAudioService).setActiveDevice(null, true);
    }

    @Test
    public void leAudioActive_whileInCall_useLeAudioForCallAndHeadsetForMedia() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        leAudioConnected(mLeAudioDevice);
        headsetConnected(mHeadsetDevice);
        a2dpConnected(mA2dpDevice);
        verify(mLeAudioService, timeout(TIMEOUT_MS)).setActiveDevice(mLeAudioDevice, false);
        verify(mA2dpService, timeout(TIMEOUT_MS)).setActiveDevice(mA2dpDevice, false);
        verify(mHeadsetService, timeout(TIMEOUT_MS)).setActiveDevice(mHeadsetDevice);

        Mockito.clearInvocations(mLeAudioService, mA2dpService, mHeadsetService);
        leAudioActiveDeviceChanged(mLeAudioDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mActiveDeviceManager.getHandlerLooper());
        verify(mA2dpService).setActiveDevice(null, true);
        verify(mHeadsetService).setActiveDevice(null);

        Mockito.clearInvocations(mLeAudioService, mA2dpService, mHeadsetService);
        mAudioModeChangedListener.onModeChanged(AudioManager.MODE_NORMAL);
        verify(mA2dpService).setActiveDevice(mA2dpDevice, false);
        verify(mLeAudioService).setActiveDevice(null, true);
    }

    /**
     * Helper to indicate A2dp connected for a device.
     */
    private void a2dpConnected(BluetoothDevice device) {
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate A2dp disconnected for a device.
     */
    private void a2dpDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice = (mDeviceConnectionStack.size() > 0)
                ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1) : null;

        Intent intent = new Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate A2dp active device changed for a device.
     */
    private void a2dpActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Headset connected for a device.
     */
    private void headsetConnected(BluetoothDevice device) {
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Headset disconnected for a device.
     */
    private void headsetDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice = (mDeviceConnectionStack.size() > 0)
                ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1) : null;

        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Headset active device changed for a device.
     */
    private void headsetActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Hearing Aid connected for a device.
     */
    private void hearingAidConnected(BluetoothDevice device) {
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Hearing Aid disconnected for a device.
     */
    private void hearingAidDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice = (mDeviceConnectionStack.size() > 0)
                ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1) : null;

        Intent intent = new Intent(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate Hearing Aid active device changed for a device.
     */
    private void hearingAidActiveDeviceChanged(BluetoothDevice device) {
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
    }

    /**
     * Helper to indicate LE Audio connected for a device.
     */
    private void leAudioConnected(BluetoothDevice device) {
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate LE Audio disconnected for a device.
     */
    private void leAudioDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice = (mDeviceConnectionStack.size() > 0)
                ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1) : null;

        Intent intent = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate LE Audio active device changed for a device.
     */
    private void leAudioActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate LE Hearing Aid connected for a device.
     */
    private void leHearingAidConnected(BluetoothDevice device) {
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate LE Hearing Aid disconnected for a device.
     */
    private void leHearingAidDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice = (mDeviceConnectionStack.size() > 0)
                ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1) : null;

        Intent intent = new Intent(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }

    /**
     * Helper to indicate LE Audio Hearing Aid device changed for a device.
     */
    private void leHearingAidActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        Intent intent = new Intent(BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mActiveDeviceManager.getBroadcastReceiver().onReceive(mContext, intent);
    }
}
