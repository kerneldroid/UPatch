// IUPRootService.aidl
package me.bmax.upatch;

import android.content.pm.PackageInfo;
import rikka.parcelablelist.ParcelableListSlice;

interface IUPRootService {
    ParcelableListSlice<PackageInfo> getPackages(int flags);
}