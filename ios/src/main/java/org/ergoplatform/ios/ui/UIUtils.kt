package org.ergoplatform.ios.ui

import org.ergoplatform.ios.Main
import org.robovm.apple.foundation.NSArray
import org.robovm.apple.foundation.NSOperationQueue
import org.robovm.apple.foundation.NSString
import org.robovm.apple.foundation.NSURL
import org.robovm.apple.uikit.UIActivityViewController
import org.robovm.apple.uikit.UIApplication
import org.robovm.apple.uikit.UIColor
import org.robovm.apple.uikit.UIViewController


const val MAX_WIDTH = 500.0
const val DEFAULT_MARGIN = 6.0

const val IMAGE_WALLET = "rectangle.on.rectangle.angled"
const val IMAGE_SETTINGS = "gear"
const val IMAGE_CREATE_WALLET = "folder.badge.plus"
const val IMAGE_RESTORE_WALLET = "arrow.clockwise"
const val IMAGE_READONLY_WALLET = "magnifyingglass"

const val FONT_SIZE_BODY1 = 18.0

// See https://developer.apple.com/design/human-interface-guidelines/ios/visual-design/color/#system-colors
val uiColorErgo get() = UIColor.systemRed()


fun getAppDelegate() = UIApplication.getSharedApplication().delegate as Main
fun runOnMainThread(r: Runnable) = NSOperationQueue.getMainQueue().addOperation(r)
fun openUrlInBrowser(url: String) = UIApplication.getSharedApplication().openURL(NSURL(url))

fun UIViewController.shareText(text: String) {
    val textShare = NSString(text)
    val texttoshare = NSArray(textShare)
    val share = UIActivityViewController(texttoshare, null)
    presentViewController(share, true, null)
}