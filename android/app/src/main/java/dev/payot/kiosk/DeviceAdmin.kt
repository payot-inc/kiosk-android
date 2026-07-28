package dev.payot.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * device owner 지정을 위한 최소 DeviceAdminReceiver.
 *
 * device owner 로 지정되면 Updater 의 PackageInstaller 설치가 사용자 확인 없이
 * 무음으로 완료된다(완전 무인 자가 업데이트). 지정 방법(계정 0개 상태에서):
 *   adb shell dpm set-device-owner dev.payot.kiosk/.DeviceAdmin
 */
class DeviceAdmin : DeviceAdminReceiver()
