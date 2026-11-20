package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.CustomerRecord
import com.example.model.DeliveryCategory
import com.example.model.DeliveryStatus
import com.example.model.DeliveryStop
import com.example.model.NoteIconType
import com.example.util.OcrResult
import com.example.viewmodel.DeliveryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.junit.Rule
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @org.junit.Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    // Clear FileProvider static cache so each Robolectric test sandbox resolves its own cacheDir/filesDir
    runCatching {
      val sCacheField = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
      sCacheField.isAccessible = true
      val cache = sCacheField.get(null) as? MutableMap<*, *>
      cache?.clear()
    }
    val repo = com.example.data.repository.DeliveryRepository.initialize(context)
    repo.clearCategoriesForTest()
    repo.clearSuspiciousCustomersForTest()
    kotlinx.coroutines.runBlocking {
      repo.clearAll()
    }
  }

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("مسار Masar", appName)
  }

  @Test
  fun `delivery viewmodel starts with empty stops list without any template models`() {
    val viewModel = DeliveryViewModel()
    val state = viewModel.uiState.value

    // No template sample orders exist by default as requested
    assertEquals(0, state.stops.size)
  }

  @Test
  fun `openNewOrderScreen opens details screen with all fields empty`() {
    val viewModel = DeliveryViewModel()
    viewModel.openNewOrderScreen()

    val state = viewModel.uiState.value
    assertTrue(state.isDetailSheetOpen)
    val newStop = state.selectedStop
    org.junit.Assert.assertNotNull(newStop)
    assertTrue(newStop!!.isNewOrder)
    assertEquals("", newStop.orderNumber)
    assertEquals("", newStop.orderCodeNumber)
    assertEquals("", newStop.phone1)
    assertEquals("", newStop.phone2)
    assertEquals("", newStop.issueNotes)
    assertNull(newStop.screenshotUriString)
  }

  @Test
  fun `saving new order adds it to stops list with strictly entered order number`() {
    val viewModel = DeliveryViewModel()
    assertEquals(0, viewModel.uiState.value.stops.size)

    viewModel.openNewOrderScreen()
    val currentSelected = viewModel.uiState.value.selectedStop!!

    val filledStop = currentSelected.copy(
      orderNumber = "163601793",
      orderCodeNumber = "163601793",
      phone1 = "+964 771 026 6414",
      phone2 = "+964 780 123 4567"
    )

    viewModel.saveOrConfirmStop(filledStop)

    val updatedState = viewModel.uiState.value
    assertEquals(1, updatedState.stops.size)
    assertEquals("163601793", updatedState.stops.first().orderNumber)
    assertEquals("163601793", updatedState.stops.first().orderCodeNumber)
    assertEquals("+964 771 026 6414", updatedState.stops.first().phone1)
    assertEquals("+964 780 123 4567", updatedState.stops.first().phone2)
    org.junit.Assert.assertFalse(updatedState.isDetailSheetOpen)
    assertNull(updatedState.selectedStop)
  }

  @Test
  fun `ocr application populates empty fields`() {
    val viewModel = DeliveryViewModel()
    viewModel.openNewOrderScreen()
    val newStop = viewModel.uiState.value.selectedStop!!
    viewModel.saveOrConfirmStop(newStop)

    val ocrData = OcrResult(
      orderNumber = "163601793",
      phone1 = "+964 771 026 6414",
      phone2 = "+964 780 123 4567",
      governorate = "البصرة - محطة الزبير",
      region = "الزبير",
      price = "25,000",
      rawText = "OCR Output"
    )

    viewModel.applyOcrResult(newStop.id, ocrData)

    val updated = viewModel.uiState.value.stops.first { it.id == newStop.id }
    assertEquals("163601793", updated.orderNumber)
    assertEquals("163601793", updated.orderCodeNumber)
    assertEquals("+964 771 026 6414", updated.phone1)
    assertEquals("+964 780 123 4567", updated.phone2)
    assertEquals("25,000", updated.price)
    assertTrue(updated.isOcrProcessed)
  }

  @Test
  fun `saving real location coordinates updates stop`() {
    val viewModel = DeliveryViewModel()
    viewModel.openNewOrderScreen()
    val newStop = viewModel.uiState.value.selectedStop!!
    viewModel.saveOrConfirmStop(newStop)

    viewModel.updateLocationCoordinates(newStop.id, 30.5081, 47.7835)

    val updated = viewModel.uiState.value.stops.first { it.id == newStop.id }
    assertTrue(updated.isLocationSaved)
    assertEquals("30.5081, 47.7835", updated.locationCoordinates)
  }

  @Test
  fun `ocr parser strictly separates order number, phones, and price without cross contamination and without currency symbol`() {
    val sampleOcrText = """
      عنوان الزبون
      رقم الطلب 163601793
      السعر: 35,000 د.ع
      البصرة - محطة الزبير
      رقم الهاتف 1
      07710266414
      رقم الهاتف 2
      07801234567
    """.trimIndent()

    val result = com.example.util.OcrHelper.parseOcrText(sampleOcrText)

    assertEquals("163601793", result.orderNumber)
    assertEquals("+964 771 026 6414", result.phone1)
    assertEquals("+964 780 123 4567", result.phone2)
    assertEquals("35,000", result.price)
    org.junit.Assert.assertNotEquals(result.orderNumber, result.phone1)
    org.junit.Assert.assertNotEquals(result.orderNumber, result.phone2)
    org.junit.Assert.assertNotEquals(result.phone1, result.phone2)
  }

  @Test
  fun `cleanPriceNumber strips currency symbols and leaves only numeric price`() {
    assertEquals("25,000", com.example.util.OcrHelper.cleanPriceNumber("25,000 د.ع"))
    assertEquals("35000", com.example.util.OcrHelper.cleanPriceNumber("35000 دينار"))
    assertEquals("50000", com.example.util.OcrHelper.cleanPriceNumber("50000 IQD"))
    assertEquals("15,000", com.example.util.OcrHelper.cleanPriceNumber("15,000"))
  }

  @Test
  fun `orders are sorted in Arabic alphabetical order by customer address`() {
    val viewModel = DeliveryViewModel()
    
    val stopY = DeliveryStop(
      id = "1", orderNumber = "101", dateTime = "الآن",
      title = "حي الياسمين", subtitle = "شارع 1", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    val stopR = DeliveryStop(
      id = "2", orderNumber = "102", dateTime = "الآن",
      title = "حي الروضة", subtitle = "شارع 2", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    val stopS = DeliveryStop(
      id = "3", orderNumber = "103", dateTime = "الآن",
      title = "حي السليمانية", subtitle = "شارع 3", note = "", status = DeliveryStatus.IN_TRANSIT
    )

    viewModel.saveOrConfirmStop(stopY)
    viewModel.saveOrConfirmStop(stopR)
    viewModel.saveOrConfirmStop(stopS)

    val stops = viewModel.uiState.value.filteredStops
    assertEquals(3, stops.size)
    // Arabic alphabetical: الروضة (ر) -> السليمانية (س) -> الياسمين (ي)
    assertTrue(stops[0].title.contains("الروضة"))
    assertTrue(stops[1].title.contains("السليمانية"))
    assertTrue(stops[2].title.contains("الياسمين"))
  }

  @Test
  fun `ocr extracts clean phones without prepending label number 1 or 2`() {
    val textWithLabels = """
      رقم الهاتف 1: 07710266414
      رقم الهاتف 2: 07801234567
      طلب: 163601793
    """.trimIndent()

    val result = com.example.util.OcrHelper.parseOcrText(textWithLabels)
    assertEquals("+964 771 026 6414", result.phone1)
    assertEquals("+964 780 123 4567", result.phone2)
    assertEquals("163601793", result.orderNumber)
    org.junit.Assert.assertFalse(result.phone1!!.contains("10771"))
    org.junit.Assert.assertFalse(result.phone2!!.contains("20780"))
  }

  @Test
  fun `updating package contents and external photos succeeds`() {
    val viewModel = DeliveryViewModel()
    viewModel.openNewOrderScreen()
    val newStop = viewModel.uiState.value.selectedStop!!
    viewModel.saveOrConfirmStop(newStop)

    viewModel.updateCapturedContentsPhoto(newStop.id, "file:///tmp/contents.jpg")
    viewModel.updateCapturedExternalPhoto(newStop.id, "file:///tmp/external.jpg")

    val updated = viewModel.uiState.value.stops.first { it.id == newStop.id }
    assertEquals("file:///tmp/contents.jpg", updated.capturedContentsPhotoUri)
    assertEquals("file:///tmp/external.jpg", updated.capturedExternalPhotoUri)
  }

  @Test
  fun `barcode search query accurately filters matching order from camera scan`() {
    val viewModel = DeliveryViewModel()
    val order1 = DeliveryStop(
      id = "ord_1", orderNumber = "163601793", orderCodeNumber = "163601793", dateTime = "الآن",
      title = "حي الروضة", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    val order2 = DeliveryStop(
      id = "ord_2", orderNumber = "889900", orderCodeNumber = "889900", dateTime = "الآن",
      title = "حي النرجس", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    viewModel.saveOrConfirmStop(order1)
    viewModel.saveOrConfirmStop(order2)
    
    // Simulate camera barcode scan result
    viewModel.setSearchQuery("163601793")

    val state = viewModel.uiState.value
    assertEquals("163601793", state.searchQuery)
    assertEquals(1, state.filteredStops.size)
    assertEquals("163601793", state.filteredStops.first().orderNumber)

    // Test digits only barcode scan
    viewModel.setSearchQuery("8899")
    val stateDigits = viewModel.uiState.value
    assertEquals(1, stateDigits.filteredStops.size)
    assertEquals("889900", stateDigits.filteredStops.first().orderNumber)
  }

  @Test
  fun `deleteStop moves order to deletedStops and updates counters`() {
    val viewModel = DeliveryViewModel()
    val order = DeliveryStop(
      id = "test_del_1", orderNumber = "123456", orderCodeNumber = "123456", dateTime = "الآن",
      title = "حي اليرموك", subtitle = "شارع 4", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    viewModel.saveOrConfirmStop(order)
    assertEquals(1, viewModel.uiState.value.activeCount)
    assertEquals(0, viewModel.uiState.value.deletedCount)

    // Delete the order
    viewModel.deleteStop(order.id)

    val state = viewModel.uiState.value
    assertEquals(0, state.activeCount)
    assertEquals(1, state.deletedCount)
    assertTrue(state.stops.first().isDeleted)

    // In normal active view: filteredStops should not contain the deleted stop
    assertEquals(0, state.filteredStops.size)

    // When toggling to deleted view (الطلبات المحذوفة): filteredStops should show the deleted stop
    viewModel.setShowingDeleted(true)
    val deletedState = viewModel.uiState.value
    assertTrue(deletedState.isShowingDeleted)
    assertEquals(1, deletedState.filteredStops.size)
    assertEquals("123456", deletedState.filteredStops.first().orderNumber)
  }

  @Test
  fun `restoreStop restores deleted order back to active list`() {
    val viewModel = DeliveryViewModel()
    val order = DeliveryStop(
      id = "test_restore_1", orderNumber = "998877", orderCodeNumber = "998877", dateTime = "الآن",
      title = "حي الكرادة", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    viewModel.saveOrConfirmStop(order)
    viewModel.deleteStop(order.id)
    assertEquals(0, viewModel.uiState.value.activeCount)
    assertEquals(1, viewModel.uiState.value.deletedCount)

    // Restore the order
    viewModel.restoreStop(order.id)

    val state = viewModel.uiState.value
    assertEquals(1, state.activeCount)
    assertEquals(0, state.deletedCount)
    org.junit.Assert.assertFalse(state.stops.first().isDeleted)
    assertEquals(1, state.filteredStops.size)
  }

  @Test
  fun `permanentlyDeleteStop permanently removes the stop`() {
    val viewModel = DeliveryViewModel()
    val order = DeliveryStop(
      id = "test_perm_del", orderNumber = "776655", orderCodeNumber = "776655", dateTime = "الآن",
      title = "حي المنصور", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    viewModel.saveOrConfirmStop(order)
    viewModel.deleteStop(order.id)
    assertEquals(1, viewModel.uiState.value.stops.size)

    // Permanently delete
    viewModel.permanentlyDeleteStop(order.id)
    val state = viewModel.uiState.value
    assertEquals(0, state.stops.size)
    assertEquals(0, state.activeCount)
    assertEquals(0, state.deletedCount)
  }

  @Test
  fun `emptyDeletedStops clears all deleted items while keeping active stops intact`() {
    val viewModel = DeliveryViewModel()
    val activeOrder = DeliveryStop(
      id = "active_1", orderNumber = "1111", orderCodeNumber = "1111", dateTime = "الآن",
      title = "حي الجامعة", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    val deletedOrder = DeliveryStop(
      id = "deleted_1", orderNumber = "2222", orderCodeNumber = "2222", dateTime = "الآن",
      title = "حي الأعظمية", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    viewModel.saveOrConfirmStop(activeOrder)
    viewModel.saveOrConfirmStop(deletedOrder)
    viewModel.deleteStop(deletedOrder.id)

    assertEquals(1, viewModel.uiState.value.activeCount)
    assertEquals(1, viewModel.uiState.value.deletedCount)

    // Empty trash
    viewModel.emptyDeletedStops()

    val state = viewModel.uiState.value
    assertEquals(1, state.activeCount)
    assertEquals(0, state.deletedCount)
    assertEquals(1, state.stops.size)
    assertEquals("1111", state.stops.first().orderNumber)
  }

  @Test
  fun `filtering by status correctly filters orders matching chosen status`() {
    val viewModel = DeliveryViewModel()
    val ordTransit = DeliveryStop(
      id = "s_transit", orderNumber = "1001", orderCodeNumber = "1001", dateTime = "الآن",
      title = "حي المنصور", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    val ordDelivered = DeliveryStop(
      id = "s_deliv", orderNumber = "1002", orderCodeNumber = "1002", dateTime = "الآن",
      title = "حي الكرادة", subtitle = "", note = "", status = DeliveryStatus.DELIVERED
    )
    val ordDelayed = DeliveryStop(
      id = "s_delay", orderNumber = "1003", orderCodeNumber = "1003", dateTime = "الآن",
      title = "حي الجادرية", subtitle = "", note = "", status = DeliveryStatus.DELAYED
    )
    val ordLineIssues = DeliveryStop(
      id = "s_line", orderNumber = "1004", orderCodeNumber = "1004", dateTime = "الآن",
      title = "حي الدورة", subtitle = "", note = "", status = DeliveryStatus.LINE_ISSUES
    )
    val ordReturned = DeliveryStop(
      id = "s_ret", orderNumber = "1005", orderCodeNumber = "1005", dateTime = "الآن",
      title = "حي الغزالية", subtitle = "", note = "", status = DeliveryStatus.RETURNED
    )

    viewModel.saveOrConfirmStop(ordTransit)
    viewModel.saveOrConfirmStop(ordDelivered)
    viewModel.saveOrConfirmStop(ordDelayed)
    viewModel.saveOrConfirmStop(ordLineIssues)
    viewModel.saveOrConfirmStop(ordReturned)

    assertEquals(5, viewModel.uiState.value.activeCount)
    assertEquals(1, viewModel.uiState.value.countForStatus(DeliveryStatus.IN_TRANSIT))
    assertEquals(1, viewModel.uiState.value.countForStatus(DeliveryStatus.DELIVERED))
    assertEquals(1, viewModel.uiState.value.countForStatus(DeliveryStatus.DELAYED))
    assertEquals(1, viewModel.uiState.value.countForStatus(DeliveryStatus.LINE_ISSUES))
    assertEquals(1, viewModel.uiState.value.countForStatus(DeliveryStatus.RETURNED))

    // Filter by DELIVERED (تم التسليم)
    viewModel.setFilter(DeliveryStatus.DELIVERED)
    var filtered = viewModel.uiState.value.filteredStops
    assertEquals(1, filtered.size)
    assertEquals("1002", filtered.first().orderNumber)

    // Filter by DELAYED (مؤجل)
    viewModel.setFilter(DeliveryStatus.DELAYED)
    filtered = viewModel.uiState.value.filteredStops
    assertEquals(1, filtered.size)
    assertEquals("1003", filtered.first().orderNumber)

    // Filter by LINE_ISSUES (مشاكل خط)
    viewModel.setFilter(DeliveryStatus.LINE_ISSUES)
    filtered = viewModel.uiState.value.filteredStops
    assertEquals(1, filtered.size)
    assertEquals("1004", filtered.first().orderNumber)

    // Filter by RETURNED (راجع للشركة)
    viewModel.setFilter(DeliveryStatus.RETURNED)
    filtered = viewModel.uiState.value.filteredStops
    assertEquals(1, filtered.size)
    assertEquals("1005", filtered.first().orderNumber)

    // Reset filter to All (الكل)
    viewModel.setFilter(null)
    assertEquals(5, viewModel.uiState.value.filteredStops.size)
  }

  @Test
  fun `updateStopStatus directly updates order status classification and notes`() {
    val viewModel = DeliveryViewModel()
    val order = DeliveryStop(
      id = "cls_1", orderNumber = "9988", orderCodeNumber = "9988", dateTime = "الآن",
      title = "حي القادسية", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT
    )
    viewModel.saveOrConfirmStop(order)

    // Classify as DELAYED (مؤجل)
    viewModel.updateStopStatus(order.id, DeliveryStatus.DELAYED)
    var updated = viewModel.uiState.value.stops.first { it.id == order.id }
    assertEquals(DeliveryStatus.DELAYED, updated.status)
    assertEquals("مؤجل", updated.status.labelAr)
    assertEquals(NoteIconType.HISTORY, updated.noteIconType)

    // Classify as RETURNED (راجع للشركة)
    viewModel.updateStopStatus(order.id, DeliveryStatus.RETURNED)
    updated = viewModel.uiState.value.stops.first { it.id == order.id }
    assertEquals(DeliveryStatus.RETURNED, updated.status)
    assertEquals("راجع للشركة", updated.status.labelAr)
    assertEquals(NoteIconType.INFO, updated.noteIconType)

    // Classify as LINE_ISSUES (مشاكل خط)
    viewModel.updateStopStatus(order.id, DeliveryStatus.LINE_ISSUES)
    updated = viewModel.uiState.value.stops.first { it.id == order.id }
    assertEquals(DeliveryStatus.LINE_ISSUES, updated.status)
    assertEquals("مشاكل خط", updated.status.labelAr)
    assertEquals(NoteIconType.ROUTE, updated.noteIconType)

    // Classify as DELIVERED (تم التسليم)
    viewModel.updateStopStatus(order.id, DeliveryStatus.DELIVERED)
    updated = viewModel.uiState.value.stops.first { it.id == order.id }
    assertEquals(DeliveryStatus.DELIVERED, updated.status)
    assertEquals("تم التسليم", updated.status.labelAr)
    assertEquals(NoteIconType.CHECK, updated.noteIconType)
  }

  @Test
  fun `saved orders persist across app restart and new ViewModel instance`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm1 = DeliveryViewModel(context)

    val order = DeliveryStop(
      id = "ord_persist_1",
      orderNumber = "163601793",
      orderCodeNumber = "163601793",
      dateTime = "الآن",
      title = "حي الروضة - شارع حفصة",
      subtitle = "فيلا 14",
      note = "تم التسليم بنجاح",
      status = DeliveryStatus.DELIVERED,
      phone1 = "+964 771 026 6414",
      phone2 = "+964 780 123 4567"
    )

    vm1.saveStopSuspending(order)

    // Simulate user exiting the app and returning (new ViewModel)
    val vm2 = DeliveryViewModel(context)
    vm2.loadSavedStopsSuspending()

    val reloadedStops = vm2.uiState.value.stops
    assertEquals(1, reloadedStops.size)
    val reloaded = reloadedStops.first()
    assertEquals("163601793", reloaded.orderNumber)
    assertEquals("حي الروضة - شارع حفصة", reloaded.title)
    assertEquals(DeliveryStatus.DELIVERED, reloaded.status)
    assertEquals("+964 771 026 6414", reloaded.phone1)
    assertEquals("+964 780 123 4567", reloaded.phone2)
  }

  @Test
  fun `category management operations add, rename, reorder, delete and reset`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)
    val initialCount = vm.uiState.value.categories.size
    assertEquals(5, initialCount)

    // 1. Add new category
    vm.addCategory("مستعجل جداً", "#EA580C")
    var categories = vm.uiState.value.categories
    assertEquals(initialCount + 1, categories.size)
    val addedCat = categories.last()
    assertEquals("مستعجل جداً", addedCat.name)
    assertEquals("#EA580C", addedCat.primaryColorHex)

    // 2. Rename category
    vm.renameCategory(addedCat.id, "طلبات VIP سريعة")
    categories = vm.uiState.value.categories
    val renamedCat = categories.first { it.id == addedCat.id }
    assertEquals("طلبات VIP سريعة", renamedCat.name)

    // 3. Move category up (reorder)
    val originalIndex = categories.indexOfFirst { it.id == addedCat.id }
    assertTrue(originalIndex > 0)
    vm.moveCategoryUp(addedCat.id)
    val newIndex = vm.uiState.value.categories.indexOfFirst { it.id == addedCat.id }
    assertEquals(originalIndex - 1, newIndex)

    // 4. Move category down
    vm.moveCategoryDown(addedCat.id)
    assertEquals(originalIndex, vm.uiState.value.categories.indexOfFirst { it.id == addedCat.id })

    // 5. Delete category
    vm.deleteCategory(addedCat.id)
    assertEquals(initialCount, vm.uiState.value.categories.size)
    assertTrue(vm.uiState.value.categories.none { it.id == addedCat.id })

    // 6. Reset to default categories
    vm.resetCategoriesToDefault()
    assertEquals(5, vm.uiState.value.categories.size)
    assertEquals("قيد التوصيل", vm.uiState.value.categories[0].name)
  }

  @Test
  fun `filtering stops by category filters correctly and null resets to all`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    val stop1 = DeliveryStop(
      id = "s1", orderNumber = "101", orderCodeNumber = "101", dateTime = "الآن",
      title = "حي الرياض", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT", categoryName = "قيد التوصيل"
    )
    val stop2 = DeliveryStop(
      id = "s2", orderNumber = "102", orderCodeNumber = "102", dateTime = "الآن",
      title = "حي النخيل", subtitle = "", note = "", status = DeliveryStatus.DELIVERED,
      categoryId = "DELIVERED", categoryName = "تم التسليم"
    )
    vm.saveOrConfirmStop(stop1)
    vm.saveOrConfirmStop(stop2)

    assertEquals(2, vm.uiState.value.activeCount)

    // Filter by IN_TRANSIT category
    vm.setCategoryFilter("IN_TRANSIT")
    var filtered = vm.uiState.value.filteredStops
    assertEquals(1, filtered.size)
    assertEquals("101", filtered.first().orderNumber)

    // Filter by DELIVERED category
    vm.setCategoryFilter("DELIVERED")
    filtered = vm.uiState.value.filteredStops
    assertEquals(1, filtered.size)
    assertEquals("102", filtered.first().orderNumber)

    // Reset filter to All (الكل)
    vm.setCategoryFilter(null)
    filtered = vm.uiState.value.filteredStops
    assertEquals(2, filtered.size)
  }

  @Test
  fun `custom categories persist to disk and reload on new ViewModel`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm1 = DeliveryViewModel(context)

    vm1.addCategory("توزيع مسائي", "#4F46E5")
    val catCount = vm1.uiState.value.categories.size
    assertEquals(6, catCount)

    // Reload with a new ViewModel instance
    val vm2 = DeliveryViewModel(context)
    val reloadedCategories = vm2.uiState.value.categories
    assertEquals(6, reloadedCategories.size)
    assertTrue(reloadedCategories.any { it.name == "توزيع مسائي" })
  }

  @Test
  fun `CustomerRecord normalizePhoneNumber correctly normalizes various Iraqi and international phone formats`() {
    val norm1 = com.example.model.CustomerRecord.normalizePhoneNumber("+964 771 026 6414")
    val norm2 = com.example.model.CustomerRecord.normalizePhoneNumber("07710266414")
    val norm3 = com.example.model.CustomerRecord.normalizePhoneNumber("009647710266414")
    val norm4 = com.example.model.CustomerRecord.normalizePhoneNumber("964-771-026-6414")
    val norm5 = com.example.model.CustomerRecord.normalizePhoneNumber("0771 026 6414")

    assertEquals("07710266414", norm1)
    assertEquals("07710266414", norm2)
    assertEquals("07710266414", norm3)
    assertEquals("07710266414", norm4)
    assertEquals("07710266414", norm5)
  }

  @Test
  fun `CustomerRecord parseCoordinates extracts valid coordinates from Google Maps URLs and plain texts`() {
    val url1 = "https://maps.google.com/?q=30.5081,47.7835"
    val pair1 = com.example.model.CustomerRecord.parseCoordinatesFromUrlOrText(url1)
    org.junit.Assert.assertNotNull(pair1)
    assertEquals(30.5081, pair1!!.first, 0.0001)
    assertEquals(47.7835, pair1.second, 0.0001)

    val url2 = "https://www.google.com/maps/@33.3152,44.3661,15z"
    val pair2 = com.example.model.CustomerRecord.parseCoordinatesFromUrlOrText(url2)
    org.junit.Assert.assertNotNull(pair2)
    assertEquals(33.3152, pair2!!.first, 0.0001)
    assertEquals(44.3661, pair2.second, 0.0001)

    val plainText = "30.5081, 47.7835"
    val pair3 = com.example.model.CustomerRecord.parseCoordinatesFromUrlOrText(plainText)
    org.junit.Assert.assertNotNull(pair3)
    assertEquals(30.5081, pair3!!.first, 0.0001)
    assertEquals(47.7835, pair3.second, 0.0001)
  }

  @Test
  fun `saving customer and finding by normalized phone returns matching customer with coordinates and address`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    val record = com.example.model.CustomerRecord(
      phone = "07710266414",
      originalPhone = "+964 771 026 6414",
      writtenAddress = "حي اليرموك - محلة 612",
      areaSubtitle = "بغداد - الكرخ",
      locationCoordinates = "33.3152,44.3661",
      latitude = 33.3152,
      longitude = 44.3661,
      lastUpdated = "2026-11-11 12:00"
    )

    // Save record to database
    val repo = com.example.data.repository.DeliveryRepository.initialize(context)
    repo.saveCustomer(record)

    // Query using different phone format (e.g. 07710266414 or +9647710266414)
    val found = vm.findCustomerByPhone("+964 771 026 6414")
    org.junit.Assert.assertNotNull(found)
    assertEquals("حي اليرموك - محلة 612", found!!.writtenAddress)
    assertEquals("بغداد - الكرخ", found.areaSubtitle)
    assertEquals(33.3152, found.latitude!!, 0.0001)
    assertEquals(44.3661, found.longitude!!, 0.0001)
  }

  @Test
  fun `CustomerRecord normalizes phone numbers accurately for Firestore document ID`() {
    assertEquals("07710266414", CustomerRecord.normalizePhoneNumber("07710266414"))
    assertEquals("07710266414", CustomerRecord.normalizePhoneNumber("009647710266414"))
    assertEquals("07710266414", CustomerRecord.normalizePhoneNumber("+964 771 026 6414"))
    assertEquals("07710266414", CustomerRecord.normalizePhoneNumber("7710266414"))
    assertEquals("07801234567", CustomerRecord.normalizePhoneNumber("9647801234567"))
  }

  @Test
  fun `CustomerRecord parses GPS coordinates and creates Google Maps link properly`() {
    val coords = CustomerRecord.parseCoordinatesFromUrlOrText("33.3152, 44.3661")
    org.junit.Assert.assertNotNull(coords)
    assertEquals(33.3152, coords!!.first, 0.0001)
    assertEquals(44.3661, coords.second, 0.0001)

    val mapUrl = CustomerRecord.buildGoogleMapsUrl(coords.first, coords.second)
    assertEquals("https://maps.google.com/?q=33.3152,44.3661", mapUrl)
  }

  @Test
  fun `search query filters orders by customer phone, order number, and address`() {
    val vm = DeliveryViewModel()
    val stop1 = DeliveryStop(
      id = "s1",
      orderNumber = "172744905",
      orderCodeNumber = "172744905",
      dateTime = "اليوم",
      title = "البصرة - محطة الزبير",
      subtitle = "محطة الزبير",
      phone1 = "07710266414",
      status = DeliveryStatus.IN_TRANSIT,
      note = ""
    )
    val stop2 = DeliveryStop(
      id = "s2",
      orderNumber = "163601888",
      orderCodeNumber = "163601888",
      dateTime = "اليوم",
      title = "بغداد - المنصور",
      subtitle = "حي المنصور",
      phone1 = "07801234567",
      status = DeliveryStatus.IN_TRANSIT,
      note = ""
    )
    vm.saveOrConfirmStop(stop1)
    vm.saveOrConfirmStop(stop2)

    // 1. Search by customer phone
    vm.setSearchQuery("0771")
    assertEquals(1, vm.uiState.value.filteredStops.size)
    assertEquals("172744905", vm.uiState.value.filteredStops.first().orderCodeNumber)

    // 2. Search by order number
    vm.setSearchQuery("1636")
    assertEquals(1, vm.uiState.value.filteredStops.size)
    assertEquals("بغداد - المنصور", vm.uiState.value.filteredStops.first().title)

    // 3. Search by address (with Arabic normalization)
    vm.setSearchQuery("الزبير")
    assertEquals(1, vm.uiState.value.filteredStops.size)
    assertEquals("172744905", vm.uiState.value.filteredStops.first().orderCodeNumber)

    // 4. Clear search query
    vm.setSearchQuery("")
    assertEquals(2, vm.uiState.value.filteredStops.size)
  }

  @Test
  fun `app defaults to in transit category on launch`() {
    val vm = DeliveryViewModel()
    assertEquals("IN_TRANSIT", vm.uiState.value.selectedCategoryId)
    assertEquals("قيد التوصيل", vm.uiState.value.selectedCategory?.name)
  }

  @Test
  fun `dark mode toggle updates ui state cleanly`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)
    org.junit.Assert.assertFalse(vm.uiState.value.isDarkMode)

    vm.toggleDarkMode(true)
    assertTrue(vm.uiState.value.isDarkMode)

    vm.toggleDarkMode(false)
    org.junit.Assert.assertFalse(vm.uiState.value.isDarkMode)
  }

  @Test
  fun `registerCustomer saves customer phone and address and can be found for floating lens`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    vm.registerCustomer(
      phone = "07712345678",
      address = "البصرة - الجزائر",
      area = "قرب تقاطع السعدي"
    )

    var found: CustomerRecord? = null
    for (i in 0 until 20) {
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
      found = vm.findCustomerByPhone("+964 771 234 5678")
      if (found != null && found.writtenAddress == "البصرة - الجزائر") break
      Thread.sleep(50)
    }

    org.junit.Assert.assertNotNull(found)
    assertEquals("07712345678", found!!.phone)
    assertEquals("البصرة - الجزائر", found.writtenAddress)
    assertEquals("قرب تقاطع السعدي", found.areaSubtitle)
  }

  @Test
  fun `ocr extractAllPhoneNumbers detects multiple screen phone numbers for floating lens`() {
    val screenText = """
      رسالة واتساب جديدة
      الرقم الاول 07710266414
      رقم احتياطي +964 780 998 1122
      رقم مكرر 0771 026 6414
    """.trimIndent()

    val detected = com.example.util.OcrHelper.extractAllPhoneNumbers(screenText)
    assertEquals(2, detected.size)
    assertEquals("+964 771 026 6414", detected[0])
    assertEquals("+964 780 998 1122", detected[1])
  }

  @Test
  fun `trash backup creates individual zip per deleted order named by order number with info and photos`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    // Create dummy photo files in cache
    val photo1 = java.io.File(context.cacheDir, "test_screen_1.jpg").apply {
      writeBytes(byteArrayOf(1, 2, 3, 4, 5))
    }
    val photo2 = java.io.File(context.cacheDir, "test_contents_1.jpg").apply {
      writeBytes(byteArrayOf(10, 20, 30, 40))
    }

    val deleted1 = DeliveryStop(
      id = "del_1",
      orderNumber = "172744905",
      orderCodeNumber = "172744905",
      dateTime = "اليوم",
      title = "البصرة - الزبير",
      subtitle = "حي المربد",
      phone1 = "07710266414",
      price = "25000",
      status = DeliveryStatus.IN_TRANSIT,
      note = "",
      screenshotUriString = android.net.Uri.fromFile(photo1).toString(),
      capturedContentsPhotoUri = android.net.Uri.fromFile(photo2).toString(),
      isDeleted = true
    )

    val deleted2 = DeliveryStop(
      id = "del_2",
      orderNumber = "88990011",
      orderCodeNumber = "88990011",
      dateTime = "أمس",
      title = "بغداد - الكرادة",
      subtitle = "",
      phone1 = "07801234567",
      price = "40000",
      status = DeliveryStatus.RETURNED,
      note = "رفض الاستلام",
      isDeleted = true
    )

    val zips = com.example.util.TrashBackupHelper.createDeletedOrdersBackupZips(
      context,
      listOf(deleted1, deleted2)
    )

    assertEquals(2, zips.size)
    assertEquals("172744905.zip", zips[0].name)
    assertEquals("88990011.zip", zips[1].name)

    // Verify ZIP #1 contents (order_info.txt, order_data.json, screenshot.jpg, contents_photo.jpg)
    val entries1 = mutableSetOf<String>()
    var infoText1 = ""
    java.util.zip.ZipInputStream(java.io.FileInputStream(zips[0])).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        entries1.add(entry.name)
        if (entry.name == "order_info.txt") {
          infoText1 = zis.readBytes().toString(Charsets.UTF_8)
        }
        entry = zis.nextEntry
      }
    }
    assertTrue(entries1.contains("order_info.txt"))
    assertTrue(entries1.contains("order_data.json"))
    assertTrue(entries1.contains("screenshot.jpg"))
    assertTrue(entries1.contains("contents_photo.jpg"))
    assertTrue(infoText1.contains("172744905"))
    assertTrue(infoText1.contains("البصرة - الزبير"))
    assertTrue(infoText1.contains("07710266414"))

    // Verify Telegram multi-file share intent uses FileProvider & ACTION_SEND_MULTIPLE
    val shareIntent = com.example.util.TrashBackupHelper.buildTelegramShareIntent(context, zips)
    org.junit.Assert.assertNotNull(shareIntent)
    val innerIntent = if (shareIntent!!.action == android.content.Intent.ACTION_CHOOSER) {
      @Suppress("DEPRECATION")
      shareIntent.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
    } else {
      shareIntent
    }
    assertEquals(android.content.Intent.ACTION_SEND_MULTIPLE, innerIntent.action)
    assertEquals("application/zip", innerIntent.type)
    @Suppress("DEPRECATION")
    val uris = innerIntent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
    assertEquals(2, uris?.size)
  }

  @Test
  fun `green color extraction picks green price exclusively and cleans currency symbols`() {
    val bitmap = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val blackPaint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
    val greenPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#16A34A") }

    // Non-green decoy number (e.g. order item count / subtotal in black)
    val blackRect = android.graphics.Rect(20, 20, 180, 70)
    canvas.drawRect(blackRect, blackPaint)

    // Green highlighted/printed price
    val greenRect = android.graphics.Rect(20, 120, 220, 180)
    canvas.drawRect(greenRect, greenPaint)

    val candidates = listOf(
      "99,000" to blackRect,
      "25,000 دينار" to greenRect
    )

    val extracted = com.example.util.OcrHelper.extractGreenColoredPriceFromBitmap(
      bitmap = bitmap,
      candidates = candidates
    )
    bitmap.recycle()

    assertEquals("25,000", extracted)
  }

  @Test
  fun `green color extraction ignores phone numbers and order numbers even if colored green`() {
    val bitmap = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val emeraldPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#10B981") }
    val phoneRect = android.graphics.Rect(10, 10, 200, 50)
    val orderRect = android.graphics.Rect(10, 70, 200, 110)
    val priceRect = android.graphics.Rect(10, 140, 200, 190)

    canvas.drawRect(phoneRect, emeraldPaint)
    canvas.drawRect(orderRect, emeraldPaint)
    canvas.drawRect(priceRect, emeraldPaint)

    val candidates = listOf(
      "07710266414" to phoneRect,
      "172744905" to orderRect,
      "42,500 د.ع" to priceRect
    )

    val extracted = com.example.util.OcrHelper.extractGreenColoredPriceFromBitmap(
      bitmap = bitmap,
      candidates = candidates,
      excludedPhoneDigits = setOf("9647710266414", "07710266414"),
      excludedOrderDigits = "172744905"
    )
    bitmap.recycle()

    assertEquals("42,500", extracted)
  }

  @Test
  fun `fallback keyword extraction detects price after keywords when no green color is found`() {
    // Plain white bitmap with NO green pixels
    val bitmap = android.graphics.Bitmap.createBitmap(200, 200, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val blackRect = android.graphics.Rect(10, 10, 150, 60)
    canvas.drawRect(blackRect, android.graphics.Paint().apply { color = android.graphics.Color.BLACK })

    val greenResult = com.example.util.OcrHelper.extractGreenColoredPriceFromBitmap(
      bitmap = bitmap,
      candidates = listOf("45,000" to blackRect)
    )
    bitmap.recycle()
    assertNull(greenResult)

    // Verify fallback parses keywords directly preceding price: المجموع، المطلوب، السعر، دينار
    assertEquals("45,000", com.example.util.OcrHelper.parseOcrText("المجموع: 45,000 د.ع").price)
    assertEquals("30000", com.example.util.OcrHelper.parseOcrText("المطلوب 30000 ألف").price)
    assertEquals("18,500", com.example.util.OcrHelper.parseOcrText("دينار 18,500").price)
    assertEquals("22000", com.example.util.OcrHelper.parseOcrText("السعر 22000 دينار").price)
    assertEquals("15,000", com.example.util.OcrHelper.cleanPriceNumber("15,000 ألف دينار"))
  }

  @Test
  fun `selection mode toggles individual cards, select all visible stops, and bulk actions`() {
    val vm = DeliveryViewModel()
    val s1 = DeliveryStop(
      id = "sel_1", orderNumber = "1001", orderCodeNumber = "1001", dateTime = "الآن",
      title = "حي الرياض", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT", categoryName = "قيد التوصيل", price = "25000"
    )
    val s2 = DeliveryStop(
      id = "sel_2", orderNumber = "1002", orderCodeNumber = "1002", dateTime = "الآن",
      title = "حي النرجس", subtitle = "", note = "", status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT", categoryName = "قيد التوصيل", price = "35000"
    )
    val s3OtherCategory = DeliveryStop(
      id = "sel_3", orderNumber = "1003", orderCodeNumber = "1003", dateTime = "الآن",
      title = "حي المنصور", subtitle = "", note = "", status = DeliveryStatus.DELIVERED,
      categoryId = "DELIVERED", categoryName = "تم التسليم", price = "15000"
    )
    vm.saveOrConfirmStop(s1)
    vm.saveOrConfirmStop(s2)
    vm.saveOrConfirmStop(s3OtherCategory)

    // Default filter is IN_TRANSIT -> 2 visible stops
    assertEquals(2, vm.uiState.value.filteredStops.size)

    // 1. Long-press / enter selection mode on s1
    vm.enterSelectionMode(s1.id)
    assertTrue(vm.uiState.value.isSelectionMode)
    assertEquals(1, vm.uiState.value.selectedCount)
    assertTrue(vm.uiState.value.selectedStopIds.contains("sel_1"))
    org.junit.Assert.assertFalse(vm.uiState.value.areAllFilteredSelected)

    // 2. Select All toggles all currently visible filtered stops (s1 and s2, NOT s3)
    vm.toggleSelectAllFilteredStops()
    assertTrue(vm.uiState.value.areAllFilteredSelected)
    assertEquals(2, vm.uiState.value.selectedCount)
    assertTrue(vm.uiState.value.selectedStopIds.containsAll(listOf("sel_1", "sel_2")))
    org.junit.Assert.assertFalse(vm.uiState.value.selectedStopIds.contains("sel_3"))

    // 3. Toggle Select All again deselects all
    vm.toggleSelectAllFilteredStops()
    assertEquals(0, vm.uiState.value.selectedCount)
    org.junit.Assert.assertFalse(vm.uiState.value.areAllFilteredSelected)

    // 4. Select s1 and s2 and bulk-delete to trash
    vm.selectAllFilteredStops()
    assertEquals(2, vm.uiState.value.selectedCount)
    vm.bulkDeleteSelectedStops()

    org.junit.Assert.assertFalse(vm.uiState.value.isSelectionMode)
    assertEquals(0, vm.uiState.value.selectedCount)
    assertEquals(1, vm.uiState.value.activeCount)
    assertEquals(2, vm.uiState.value.deletedCount)

    // 5. Switch to Trash, Select All deleted orders, and bulk restore
    vm.setShowingDeleted(true)
    vm.selectAllFilteredStops()
    assertEquals(2, vm.uiState.value.selectedCount)
    vm.bulkRestoreSelectedStops()

    org.junit.Assert.assertFalse(vm.uiState.value.isSelectionMode)
    assertEquals(3, vm.uiState.value.activeCount)
    assertEquals(0, vm.uiState.value.deletedCount)
  }

  @Test
  fun `updateStopStatus syncs categoryId and persists DELIVERED status across reload`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm1 = DeliveryViewModel(context)
    val order = DeliveryStop(
      id = "stat_sync_1",
      orderNumber = "554433",
      orderCodeNumber = "554433",
      dateTime = "الآن",
      title = "حي الجامعة",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT",
      categoryName = "قيد التوصيل"
    )
    vm1.saveStopSuspending(order)

    // Move order to DELIVERED via updateStopStatus
    vm1.updateStopStatus(order.id, DeliveryStatus.DELIVERED)
    val inMemory = vm1.uiState.value.stops.first { it.id == order.id }
    assertEquals(DeliveryStatus.DELIVERED, inMemory.status)
    assertEquals("DELIVERED", inMemory.categoryId)
    assertEquals("تم التسليم", inMemory.categoryName)

    // Wait briefly for coroutine persistence and verify reload keeps DELIVERED (never jumps back to IN_TRANSIT)
    vm1.saveStopSuspending(inMemory)
    val vm2 = DeliveryViewModel(context)
    vm2.loadSavedStopsSuspending()
    val reloaded = vm2.uiState.value.stops.first { it.id == order.id }
    assertEquals(DeliveryStatus.DELIVERED, reloaded.status)
    assertEquals("DELIVERED", reloaded.categoryId)
    assertEquals("تم التسليم", reloaded.categoryName)
  }

  @Test
  fun `custom category assignment persists across reload without reverting to IN_TRANSIT`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm1 = DeliveryViewModel(context)
    vm1.addCategory("طلبات مسائية", "#4F46E5")
    val customCat = vm1.uiState.value.categories.first { it.name == "طلبات مسائية" }

    val order = DeliveryStop(
      id = "custom_cat_ord_1",
      orderNumber = "778899",
      orderCodeNumber = "778899",
      dateTime = "الآن",
      title = "حي المنصور",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = customCat.id,
      categoryName = customCat.name,
      categoryColorHex = customCat.primaryColorHex
    )
    vm1.saveStopSuspending(order)

    val vm2 = DeliveryViewModel(context)
    vm2.loadSavedStopsSuspending()
    val reloaded = vm2.uiState.value.stops.first { it.id == order.id }
    assertEquals(customCat.id, reloaded.categoryId)
    assertEquals("طلبات مسائية", reloaded.categoryName)
    assertEquals("#4F46E5", reloaded.categoryColorHex)
  }

  @Test
  fun `order photos are saved in persistent filesDir order_images instead of volatile cacheDir`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val outputUri = com.example.ui.components.createHdOutputFileUri(context, "test_persistent.jpg")
    val persistentFile = java.io.File(java.io.File(context.filesDir, "order_images"), "test_persistent.jpg")
    // Verify URI points to order_images and persistent directory is created
    assertTrue(outputUri.toString().contains("order_images"))
    assertTrue(persistentFile.parentFile!!.exists())
    org.junit.Assert.assertFalse(outputUri.toString().contains("cache_images"))
  }

  @Test
  fun `handleSharedScreenshot when details sheet is closed creates new order without overwriting existing order photos`() {
    val vm = DeliveryViewModel()
    val existing = DeliveryStop(
      id = "ord_existing_1",
      orderNumber = "112233",
      orderCodeNumber = "112233",
      dateTime = "الآن",
      title = "حي الكرادة",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      screenshotUriString = "content://original_screenshot.jpg"
    )
    vm.saveOrConfirmStop(existing)

    // Details sheet is closed; sharing a new screenshot must create a NEW order
    vm.handleSharedScreenshot("content://new_shared_screenshot.jpg")

    val stops = vm.uiState.value.stops
    assertEquals(2, stops.size)
    val untouchedExisting = stops.first { it.id == "ord_existing_1" }
    assertEquals("content://original_screenshot.jpg", untouchedExisting.screenshotUriString)
    val newlyCreated = vm.uiState.value.selectedStop
    org.junit.Assert.assertNotNull(newlyCreated)
    assertEquals("content://new_shared_screenshot.jpg", newlyCreated!!.screenshotUriString)
  }

  @Test
  fun `currency symbol extraction recognizes prices before and after Iraqi and international symbols`() {
    val iqBefore = com.example.util.OcrHelper.parseOcrText("IQD 25,000")
    assertEquals("25,000", iqBefore.price)
    assertTrue(iqBefore.isPriceFromCurrency)
    assertEquals("IQD", iqBefore.detectedCurrencySymbol)

    val iqAfter = com.example.util.OcrHelper.parseOcrText("37,500 د.ع")
    assertEquals("37,500", iqAfter.price)
    assertTrue(iqAfter.isPriceFromCurrency)

    val usdPrefix = com.example.util.OcrHelper.parseOcrText("$45.50")
    assertEquals("45.50", usdPrefix.price)
    assertTrue(usdPrefix.isPriceFromCurrency)
    assertEquals("$", usdPrefix.detectedCurrencySymbol)

    val sarAfter = com.example.util.OcrHelper.parseOcrText("120 ر.س")
    assertEquals("120", sarAfter.price)
    assertTrue(sarAfter.isPriceFromCurrency)

    val eurAfter = com.example.util.OcrHelper.parseOcrText("89.90 €")
    assertEquals("89.90", eurAfter.price)
    assertTrue(eurAfter.isPriceFromCurrency)
  }

  @Test
  fun `currency symbol extraction prefers total price over delivery fee and avoids order number collision`() {
    val receiptText = """
      سعر المنتجات: 25,000 د.ع
      التوصيل: 5,000 د.ع
      المجموع: 30,000 د.ع
    """.trimIndent()
    val result = com.example.util.OcrHelper.parseOcrText(receiptText)
    assertEquals("30,000", result.price)
    assertTrue(result.isPriceFromCurrency)

    // 6-digit price with currency symbol without order label must not be stolen as orderNumber
    val sixDigitPrice = com.example.util.OcrHelper.parseOcrText("150000 IQD\n07710266414")
    assertEquals("150000", sixDigitPrice.price)
    assertTrue(sixDigitPrice.isPriceFromCurrency)
    assertNull(sixDigitPrice.orderNumber)
  }

  @Test
  fun `screen content recognition extracts number next to السعر or السعر الكلي or المبلغ with currency symbol`() {
    val case1 = com.example.util.OcrHelper.parseOcrText("السعر الكلي: 35,000 د.ع")
    assertEquals("35,000", case1.price)
    assertTrue(case1.isPriceFromLabelAndCurrency)

    val case2 = com.example.util.OcrHelper.parseOcrText("المبلغ 42,000 دينار")
    assertEquals("42,000", case2.price)
    assertTrue(case2.isPriceFromLabelAndCurrency)

    val case3 = com.example.util.OcrHelper.parseOcrText("السعر: 25000 IQD")
    assertEquals("25000", case3.price)
    assertTrue(case3.isPriceFromLabelAndCurrency)

    // Reversed visual row order (LTR scan of RTL layout)
    val reversedOrder = com.example.util.OcrHelper.parseOcrText("38,500 د.ع السعر الكلي")
    assertEquals("38,500", reversedOrder.price)
    assertTrue(reversedOrder.isPriceFromLabelAndCurrency)

    // Stacked 2-line layout (label on line 1, number + currency on line 2)
    val stackedLayout = com.example.util.OcrHelper.parseOcrText("التوصيل: 5,000 د.ع\nالسعر الكلي\n55,000 د.ع")
    assertEquals("55,000", stackedLayout.price)
    assertTrue(stackedLayout.isPriceFromLabelAndCurrency)

    // Verify ViewModel pulls extracted price cleanly into new order's price field
    val vm = DeliveryViewModel()
    vm.openNewOrderWithPrefill("35,000 د.ع", "07710266414")
    val prefilled = vm.uiState.value.selectedStop
    org.junit.Assert.assertNotNull(prefilled)
    assertTrue(vm.uiState.value.isDetailSheetOpen)
    assertEquals("35,000", prefilled!!.price)
    assertEquals("07710266414", prefilled.phone1)
  }

  @Test
  fun `lower-left target region crop and candidate extraction picks 90,000 over quantity and dates`() {
    // Verify ROI rect targets lower-left portion of screen (x: 0..64%, y: 42%..99%)
    val roiRect = com.example.util.OcrHelper.getLowerLeftPriceRegionRect(1080, 2400)
    assertEquals(0, roiRect.left)
    assertEquals((2400 * 0.42f).toInt(), roiRect.top)
    assertEquals((1080 * 0.64f).toInt(), roiRect.right)
    assertEquals((2400 * 0.99f).toInt(), roiRect.bottom)

    // Verify crop + upscale produces sharp enlarged ROI bitmap
    val fullBitmap = android.graphics.Bitmap.createBitmap(500, 1000, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(fullBitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val cropped = com.example.util.OcrHelper.createUpscaledLowerLeftCrop(fullBitmap)
    org.junit.Assert.assertNotNull(cropped)
    assertTrue(cropped!!.width > roiRect.width() / 2)
    assertTrue(cropped.width >= (500 * 0.62f).toInt())

    // Draw thin green anti-aliased strokes inside cropped ROI box for "90,000"
    val roiCanvas = android.graphics.Canvas(cropped)
    val thinGreenPaint = android.graphics.Paint().apply {
      color = android.graphics.Color.parseColor("#16A34A")
      strokeWidth = 3f
      style = android.graphics.Paint.Style.STROKE
    }
    val priceBox = android.graphics.Rect(40, cropped.height - 180, 260, cropped.height - 110)
    roiCanvas.drawRect(priceBox, thinGreenPaint)

    val greenPicked = com.example.util.OcrHelper.extractGreenColoredPriceFromBitmap(
      bitmap = cropped,
      candidates = listOf("90,000" to priceBox)
    )
    assertEquals("90,000", greenPicked)

    // Verify extractPriceFromLowerLeftCandidates picks "90,000" from lower-left Accessibility/OCR text
    // while ignoring quantity "1", year "2026", phone "+964 771 108 1368", and order number "151434495"
    val extractedLowerLeft = com.example.util.OcrHelper.extractPriceFromLowerLeftCandidates(
      visionText = null,
      bitmap = null,
      roiVisionText = null,
      roiBitmap = cropped,
      extraRoiText = "ملاحظة 2026\n1\n90,000\n151434495",
      excludedPhoneDigits = setOf("9647711081368"),
      excludedOrderDigits = "151434495"
    )
    assertEquals("90,000", extractedLowerLeft)

    cropped.recycle()
    fullBitmap.recycle()
  }

  @Test
  fun `registering a new order while detail sheet is open preserves all existing database orders`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val repo = com.example.data.repository.DeliveryRepository.initialize(context)

    val existing1 = DeliveryStop(
      id = "existing_1",
      orderNumber = "100001",
      orderCodeNumber = "100001",
      dateTime = "أمس",
      title = "البصرة - حي الجزائر",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT",
      categoryName = "قيد التوصيل",
      price = "25,000"
    )
    val existing2 = DeliveryStop(
      id = "existing_2",
      orderNumber = "100002",
      orderCodeNumber = "100002",
      dateTime = "أمس",
      title = "بغداد - الكرادة",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT",
      categoryName = "قيد التوصيل",
      price = "40,000"
    )
    repo.saveStop(existing1)
    repo.saveStop(existing2)

    val vm = DeliveryViewModel(context)
    // Simulate user immediately opening a new order (e.g. from Floating Lens or + button)
    // BEFORE initial Room load finishes
    vm.openNewOrderWithPrefill("55,000 د.ع", "07711112222")
    assertTrue(vm.uiState.value.isDetailSheetOpen)

    // Room load finishes while detail sheet is open -> must STILL merge existing orders!
    vm.loadSavedStopsSuspending()
    assertEquals(2, vm.uiState.value.stops.size)
    assertTrue(vm.uiState.value.isDetailSheetOpen)

    val pendingNewOrder = vm.uiState.value.selectedStop!!.copy(
      orderNumber = "100003",
      orderCodeNumber = "100003",
      title = "الزبير - حي الشهداء",
      governorate = "الزبير - حي الشهداء"
    )
    vm.saveOrConfirmStop(pendingNewOrder)

    val finalStops = vm.uiState.value.stops
    assertEquals(3, finalStops.size)
    assertTrue(finalStops.any { it.id == "existing_1" })
    assertTrue(finalStops.any { it.id == "existing_2" })
    assertTrue(finalStops.any { it.orderCodeNumber == "100003" })
    assertEquals(3, vm.uiState.value.filteredStops.size)
  }

  @Test
  fun `shared screenshot order and loadSavedStops preserve both existing DB orders and new order`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val repo = com.example.data.repository.DeliveryRepository.initialize(context)

    val existing = DeliveryStop(
      id = "db_order_1",
      orderNumber = "777888",
      orderCodeNumber = "777888",
      dateTime = "أمس",
      title = "حي النرجس",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT",
      categoryName = "قيد التوصيل"
    )
    repo.saveStop(existing)

    val vm = DeliveryViewModel(context)
    vm.handleSharedScreenshot("file:///tmp/shared_shot.jpg")
    vm.loadSavedStopsSuspending()

    assertEquals(2, vm.uiState.value.stops.size)
    assertTrue(vm.uiState.value.stops.any { it.id == "db_order_1" })
    assertTrue(vm.uiState.value.stops.any { it.screenshotUriString == "file:///tmp/shared_shot.jpg" })
  }

  @Test
  fun `saving new order resets trash view and search query so orders are visible immediately`() {
    val vm = DeliveryViewModel()
    val existing = DeliveryStop(
      id = "ord_a",
      orderNumber = "111222",
      orderCodeNumber = "111222",
      dateTime = "اليوم",
      title = "حي الرياض",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT",
      categoryName = "قيد التوصيل"
    )
    vm.saveOrConfirmStop(existing)
    vm.setShowingDeleted(true)
    vm.setSearchQuery("999999")

    vm.openNewOrderScreen()
    val newOrder = vm.uiState.value.selectedStop!!.copy(
      orderNumber = "333444",
      orderCodeNumber = "333444",
      title = "حي الجامعة"
    )
    vm.saveOrConfirmStop(newOrder)

    org.junit.Assert.assertFalse(vm.uiState.value.isShowingDeleted)
    assertEquals("", vm.uiState.value.searchQuery)
    assertEquals(2, vm.uiState.value.filteredStops.size)
  }

  @Test
  fun `first-time customer address entry on new order stays visible after saving`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)
    vm.openNewOrderScreen()

    composeTestRule.setContent {
      val state = vm.uiState.collectAsStateWithLifecycle().value
      val stop = state.selectedStop
      if (stop != null) {
        com.example.ui.components.CustomerOrderDetailsScreen(
          stop = stop,
          onBack = {},
          onConfirmDelivery = { vm.saveOrConfirmStop(it) },
          onScreenshotChanged = {},
          onApplyOcr = {},
          onSaveOrderFields = { code, p1, p2, notes, price ->
            vm.updateOrderFields(stop.id, code, p1, p2, notes, price)
          },
          onIssuePhotoCaptured = {},
          onCustomerAddressSaved = { addr, area ->
            vm.updateCustomerAddress(stop.id, addr, area)
          },
          categories = state.categories
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("btn_edit_address")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()

    val enteredAddress = "البصرة - حي الجزائر - قرب المجمع"
    composeTestRule.onNodeWithTag("input_edit_customer_address").performTextInput(enteredAddress)
    composeTestRule.onNodeWithTag("btn_save_customer_address").performClick()
    composeTestRule.waitForIdle()

    // Address must stay visible immediately on first entry (never reset to empty because isNewOrder == true)
    assertEquals(enteredAddress, vm.uiState.value.selectedStop?.title)
    composeTestRule.onNodeWithTag("text_customer_address_value", useUnmergedTree = true)
      .performScrollTo()
      .assertTextEquals(enteredAddress)
  }

  @Test
  fun `searching for an order automatically searches across all categories`() {
    val vm = DeliveryViewModel()
    val inTransit = DeliveryStop(
      id = "cat_1",
      orderNumber = "101010",
      orderCodeNumber = "101010",
      dateTime = "اليوم",
      title = "البصرة - حي الجزائر",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT",
      categoryName = "قيد التوصيل",
      phone1 = "07710001111"
    )
    val delivered = DeliveryStop(
      id = "cat_2",
      orderNumber = "202020",
      orderCodeNumber = "202020",
      dateTime = "اليوم",
      title = "بغداد - المنصور",
      subtitle = "",
      note = "",
      status = DeliveryStatus.DELIVERED,
      categoryId = "DELIVERED",
      categoryName = "تم التسليم",
      phone1 = "07802223333"
    )
    val delayed = DeliveryStop(
      id = "cat_3",
      orderNumber = "303030",
      orderCodeNumber = "303030",
      dateTime = "اليوم",
      title = "النجف - حي الأمير",
      subtitle = "",
      note = "",
      status = DeliveryStatus.DELAYED,
      categoryId = "DELAYED",
      categoryName = "مؤجل",
      phone1 = "07719998888"
    )
    val customCatStop = DeliveryStop(
      id = "cat_4",
      orderNumber = "404040",
      orderCodeNumber = "404040",
      dateTime = "اليوم",
      title = "كربلاء - حي الحسين",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "custom_vip",
      categoryName = "VIP",
      phone1 = "07504445555"
    )

    vm.saveOrConfirmStop(inTransit)
    vm.saveOrConfirmStop(delivered)
    vm.saveOrConfirmStop(delayed)
    vm.saveOrConfirmStop(customCatStop)

    // Default filter is IN_TRANSIT -> only 1 stop visible initially
    assertEquals("IN_TRANSIT", vm.uiState.value.selectedCategoryId)
    assertEquals(1, vm.uiState.value.filteredStops.size)

    // Search for a DELIVERED order by order number while still on IN_TRANSIT
    vm.setSearchQuery("202020")
    assertEquals(1, vm.uiState.value.filteredStops.size)
    assertEquals("cat_2", vm.uiState.value.filteredStops.first().id)

    // Search for a DELAYED order by address
    vm.setSearchQuery("الأمير")
    assertEquals(1, vm.uiState.value.filteredStops.size)
    assertEquals("cat_3", vm.uiState.value.filteredStops.first().id)

    // Search for a custom-category order by phone
    vm.setSearchQuery("0750444")
    assertEquals(1, vm.uiState.value.filteredStops.size)
    assertEquals("cat_4", vm.uiState.value.filteredStops.first().id)

    // Clearing the search restores the active IN_TRANSIT filter automatically
    vm.setSearchQuery("")
    assertEquals("IN_TRANSIT", vm.uiState.value.selectedCategoryId)
    assertEquals(1, vm.uiState.value.filteredStops.size)
    assertEquals("cat_1", vm.uiState.value.filteredStops.first().id)
  }

  @Test
  fun `adding suspicious customer persists and matches normalized Iraqi phone variants`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    vm.addSuspiciousCustomer("+964 771 026 6414", "استلم الطلب ورفض الدفع")
    assertEquals(1, vm.uiState.value.suspiciousCustomers.size)

    // Matching via local "07..." or "7..." or international "+964..." must resolve cleanly
    val foundLocal = vm.findSuspiciousCustomer("07710266414")
    val foundShort = vm.findSuspiciousCustomer("7710266414")
    org.junit.Assert.assertNotNull(foundLocal)
    org.junit.Assert.assertNotNull(foundShort)
    assertEquals("استلم الطلب ورفض الدفع", foundLocal!!.note)

    // Persisted across a fresh ViewModel instance
    val reloadedVm = DeliveryViewModel(context)
    assertEquals(1, reloadedVm.uiState.value.suspiciousCustomers.size)
    org.junit.Assert.assertNotNull(reloadedVm.findSuspiciousCustomer("07710266414"))

    // Removing deletes from state and persistence
    reloadedVm.removeSuspiciousCustomer("07710266414")
    assertEquals(0, reloadedVm.uiState.value.suspiciousCustomers.size)
    assertNull(reloadedVm.findSuspiciousCustomer("07710266414"))
  }

  @Test
  fun `SettingsScreen adds suspicious number and entering it in order details triggers vibration and warning note`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)
    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
    val vibrator = vibratorManager.defaultVibrator
    val shadowVibrator = Shadows.shadowOf(vibrator)

    val showOrderDetails = androidx.compose.runtime.mutableStateOf(false)

    composeTestRule.setContent {
      val state = vm.uiState.collectAsStateWithLifecycle().value
      if (!showOrderDetails.value) {
        com.example.ui.components.SettingsScreen(
          isDarkMode = state.isDarkMode,
          onToggleDarkMode = {},
          registeredCustomers = state.registeredCustomers,
          prefillPhone = state.prefillCustomerPhone,
          onClearPrefillPhone = {},
          onRegisterCustomer = { _, _, _ -> },
          onLookupCustomerByPhone = { null },
          isFirestoreSyncing = false,
          onSyncFirestore = {},
          suspiciousCustomers = state.suspiciousCustomers,
          onAddSuspiciousCustomer = { p, n -> vm.addSuspiciousCustomer(p, n) },
          onRemoveSuspiciousCustomer = { p -> vm.removeSuspiciousCustomer(p) }
        )
      } else {
        val stop = state.selectedStop
        if (stop != null) {
          com.example.ui.components.CustomerOrderDetailsScreen(
            stop = stop,
            onBack = {},
            onConfirmDelivery = {},
            onScreenshotChanged = {},
            onApplyOcr = {},
            onSaveOrderFields = { code, p1, p2, notes, price ->
              vm.updateOrderFields(stop.id, code, p1, p2, notes, price)
            },
            onIssuePhotoCaptured = {},
            categories = state.categories,
            suspiciousCustomers = state.suspiciousCustomers
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("input_suspicious_phone")
      .performScrollTo()
      .performTextInput("07715559999")
    composeTestRule.onNodeWithTag("input_suspicious_note")
      .performScrollTo()
      .performTextInput("سرق طلب سابق")
    composeTestRule.onNodeWithTag("btn_add_suspicious_phone")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()

    // Added number is listed in Settings suspicious customers card
    composeTestRule.onNodeWithTag("suspicious_item_07715559999")
      .performScrollTo()
      .assertIsDisplayed()

    // Now open a new order and type the suspicious phone number into Phone 1
    vm.openNewOrderScreen()
    showOrderDetails.value = true
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("edit_رقم الهاتف 1")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("input_رقم الهاتف 1")
      .performTextInput("07715559999")
    composeTestRule.waitForIdle()

    // Warning note banner must appear AND phone vibrator must have vibrated
    composeTestRule.onNodeWithTag("banner_suspicious_customer_warning")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule.onNodeWithText("تحذير: هذا الزبون مشبوه بسرقة الطلبات!", useUnmergedTree = true)
      .assertIsDisplayed()
    composeTestRule.onNodeWithText("الملاحظة: سرق طلب سابق", useUnmergedTree = true)
      .assertIsDisplayed()
    assertTrue(shadowVibrator.isVibrating)
  }

  @Test
  fun `QuickCustomerNoteParser extracts only 11-digit or country-code phones and leaves address empty for manual entry`() {
    val sampleScreen = """
      بحث 2 صورة خلفية الملاحظة
      07875160858 - بغداد المنصور
      143849759
      ٠٧٨٠١٢٣٤٥٦٧
      +964 771 555 6666
      00964 770 111 2222
      9647719998888
      1234567890
      السعر 25,000 د.ع
    """.trimIndent()

    val res = com.example.util.QuickCustomerNoteParser.parseFromScreenText(sampleScreen)
    assertEquals(5, res.entries.size)
    assertEquals("07875160858", res.entries[0].phone)
    assertEquals("", res.entries[0].address)
    assertEquals("", res.entries[0].area)

    assertEquals("07801234567", res.entries[1].phone)
    assertEquals("", res.entries[1].address)

    assertEquals("07715556666", res.entries[2].phone)
    assertEquals("07701112222", res.entries[3].phone)
    assertEquals("07719998888", res.entries[4].phone)

    // Ensure 9-digit (143849759) and 10-digit without country code (1234567890) were rejected
    assertTrue(res.entries.none { it.phone.contains("143849759") })
    assertTrue(res.entries.none { it.phone.contains("1234567890") })
    assertTrue(res.candidateLines.isEmpty())
  }

  @Test
  fun `Settings tab renders Floating Quick Add plus button overlay card and toggle`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    composeTestRule.setContent {
      DeliveryAddressesScreen(viewModel = vm)
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("tab_settings").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("card_floating_quick_add_settings")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule.onNodeWithTag("switch_floating_quick_add")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_floating_quick_add")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule.onNodeWithText("زر الإضافة السريعة العائم (+)", useUnmergedTree = true)
      .assertIsDisplayed()
  }

  @Test
  fun `edits and custom or built-in categories survive rapid saves and closing and reopening the app`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    vm.addCategory("طلبات خاصة", "#7C3AED")
    val customCat = vm.uiState.value.categories.first { it.name == "طلبات خاصة" }

    val orderA = DeliveryStop(
      id = "persist_a",
      orderNumber = "",
      orderCodeNumber = "",
      dateTime = "اليوم",
      title = "",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT",
      categoryName = "قيد التوصيل",
      createdAt = 1700000100000L
    )
    vm.saveOrConfirmStop(orderA)

    // Simulate rapid sequential field edits + address edit + GPS + category switches
    vm.openDetailSheet(vm.uiState.value.stops.first { it.id == "persist_a" })
    vm.updateOrderFields(
      stopId = "persist_a",
      orderCode = "778899",
      p1 = "07710002222",
      p2 = "07801113333",
      notes = "اتصال قبل الوصول",
      price = "45,000"
    )
    vm.updateCustomerAddress("persist_a", "بغداد - الكرادة", "قرب ساحة كهرمانة")
    vm.updateLocationCoordinates("persist_a", 33.3152, 44.3661)
    vm.updateStopCategory("persist_a", customCat)
    vm.closeDetailSheet()

    val orderB = DeliveryStop(
      id = "persist_b",
      orderNumber = "556677",
      orderCodeNumber = "556677",
      dateTime = "اليوم",
      title = "البصرة - العشار",
      subtitle = "",
      note = "",
      status = DeliveryStatus.DELAYED,
      categoryId = "DELAYED",
      categoryName = "مؤجل",
      phone1 = "07723334444",
      price = "30,000",
      createdAt = 1700000200000L
    )
    vm.saveOrConfirmStop(orderB)

    // Reopen fresh ViewModel from persisted database + JSON files
    val reopenedVm = DeliveryViewModel(context)
    val reloaded = kotlinx.coroutines.runBlocking {
      reopenedVm.loadSavedStopsSuspending()
    }

    val reloadedA = reloaded.firstOrNull { it.id == "persist_a" }
    val reloadedB = reloaded.firstOrNull { it.id == "persist_b" }
    org.junit.Assert.assertNotNull(reloadedA)
    org.junit.Assert.assertNotNull(reloadedB)

    assertEquals("778899", reloadedA!!.orderCodeNumber)
    assertEquals("07710002222", reloadedA.phone1)
    assertEquals("07801113333", reloadedA.phone2)
    assertEquals("اتصال قبل الوصول", reloadedA.issueNotes)
    assertEquals("45,000", reloadedA.price)
    assertEquals("بغداد - الكرادة", reloadedA.title)
    assertEquals("قرب ساحة كهرمانة", reloadedA.subtitle)
    assertTrue(reloadedA.isLocationSaved)
    assertEquals(customCat.id, reloadedA.categoryId)
    assertEquals("طلبات خاصة", reloadedA.categoryName)
    assertEquals(1700000100000L, reloadedA.createdAt)

    assertEquals("DELAYED", reloadedB!!.categoryId)
    assertEquals(DeliveryStatus.DELAYED, reloadedB.status)
    assertEquals("مؤجل", reloadedB.categoryName)
    assertEquals(1700000200000L, reloadedB.createdAt)
  }

  @Test
  fun `selecting category chip and pressing Back in CustomerOrderDetailsScreen persists edits and category`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)
    vm.openNewOrderScreen()
    val newId = vm.uiState.value.selectedStop!!.id

    composeTestRule.setContent {
      val state = vm.uiState.collectAsStateWithLifecycle().value
      val stop = state.selectedStop
      if (stop != null) {
        com.example.ui.components.CustomerOrderDetailsScreen(
          stop = stop,
          onBack = { vm.closeDetailSheet() },
          onConfirmDelivery = { vm.saveOrConfirmStop(it) },
          onScreenshotChanged = { uri -> vm.updateScreenshotForStop(stop.id, uri) },
          onApplyOcr = { ocr -> vm.applyOcrResult(stop.id, ocr) },
          onSaveOrderFields = { code, p1, p2, notes, price ->
            vm.updateOrderFields(stop.id, code, p1, p2, notes, price)
          },
          onIssuePhotoCaptured = {},
          onCustomerAddressSaved = { addr, area -> vm.updateCustomerAddress(stop.id, addr, area) },
          categories = state.categories,
          onCategorySelected = { cat -> vm.updateStopCategory(stop.id, cat) },
          onStatusSelected = { st -> vm.updateStopStatus(stop.id, st) }
        )
      }
    }

    composeTestRule.waitForIdle()

    // Edit customer address manually
    composeTestRule.onNodeWithTag("btn_edit_address").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("input_edit_customer_address").performTextInput("النجف - حي السعد")
    composeTestRule.onNodeWithTag("btn_save_customer_address").performClick()
    composeTestRule.waitForIdle()

    // Edit price to a negative refund amount (-15,000) via toggle chip
    composeTestRule.onNodeWithTag("btn_edit_price").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("input_edit_order_price").performTextInput("15000")
    composeTestRule.onNodeWithTag("chip_toggle_negative_price").performClick()
    composeTestRule.onNodeWithTag("btn_save_order_price").performClick()
    composeTestRule.waitForIdle()

    // Select DELAYED category chip and press Back without pressing Confirm button
    composeTestRule.onNodeWithTag("category_chip_DELAYED").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("btn_back_from_details").performClick()
    composeTestRule.waitForIdle()

    // Reopen fresh ViewModel from Room
    val reopenedVm = DeliveryViewModel(context)
    val persisted = kotlinx.coroutines.runBlocking {
      reopenedVm.loadSavedStopsSuspending()
    }.firstOrNull { it.id == newId }

    org.junit.Assert.assertNotNull(persisted)
    assertEquals("النجف - حي السعد", persisted!!.title)
    assertEquals("-15000", persisted.price)
    assertEquals("DELAYED", persisted.categoryId)
    assertEquals(DeliveryStatus.DELAYED, persisted.status)
  }

  @Test
  fun `OcrHelper recognizes zero and negative or red refund prices`() {
    // 1. cleanPriceNumber cases
    assertEquals("0", com.example.util.OcrHelper.cleanPriceNumber("0"))
    assertEquals("0", com.example.util.OcrHelper.cleanPriceNumber("0 د.ع"))
    assertEquals("0", com.example.util.OcrHelper.cleanPriceNumber("-0"))
    assertEquals("-15,000", com.example.util.OcrHelper.cleanPriceNumber("-15,000"))
    assertEquals("-15,000", com.example.util.OcrHelper.cleanPriceNumber("- 15,000 د.ع"))
    assertEquals("-25,000", com.example.util.OcrHelper.cleanPriceNumber("−25,000 د.ع"))
    assertEquals("-15,000", com.example.util.OcrHelper.cleanPriceNumber("15,000- د.ع"))

    // 2. parseOcrText for zero & negative prices
    assertEquals("0", com.example.util.OcrHelper.parseOcrText("رقم الطلب: 123456\nالسعر: 0").price)
    assertEquals("0", com.example.util.OcrHelper.parseOcrText("المجموع 0 د.ع").price)
    assertEquals("-20,000", com.example.util.OcrHelper.parseOcrText("السعر الكلي: -20,000 د.ع").price)
    assertEquals("-35,000", com.example.util.OcrHelper.parseOcrText("د.ع -35,000").price)

    // 3. Lower-left ROI candidate extraction keeps "0" instead of rejecting as 1-digit quantity
    val roiZero = com.example.util.OcrHelper.extractPriceFromLowerLeftCandidates(
      visionText = null,
      bitmap = null,
      extraRoiText = "السعر الكلي (مع التوصيل)\n0"
    )
    assertEquals("0", roiZero)

    // 4. Red bitmap pixel detector converts red refund numbers into negative "-..." prices
    val bitmap = android.graphics.Bitmap.createBitmap(120, 40, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.rgb(220, 38, 38))
    val box = android.graphics.Rect(10, 5, 110, 35)
    assertEquals(
      "-25,000",
      com.example.util.OcrHelper.extractRedColoredPriceFromBitmap(
        bitmap = bitmap,
        candidates = listOf("25,000" to box)
      )
    )
    assertEquals(
      "-25,000",
      com.example.util.OcrHelper.extractRedColoredPriceFromBitmap(
        bitmap = bitmap,
        candidates = listOf("-25,000 د.ع" to box)
      )
    )
    bitmap.recycle()
  }

  @Test
  fun `DeliveryLocationCard displays zero and negative prices and persists them across reopen`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    val zeroStop = DeliveryStop(
      id = "price_zero",
      orderNumber = "900001",
      orderCodeNumber = "900001",
      dateTime = "اليوم",
      title = "بغداد - الزيونة",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT",
      categoryName = "قيد التوصيل",
      phone1 = "07711112222",
      price = "0"
    )
    val negStop = DeliveryStop(
      id = "price_neg",
      orderNumber = "900002",
      orderCodeNumber = "900002",
      dateTime = "اليوم",
      title = "البصرة - الجزائر",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT",
      categoryName = "قيد التوصيل",
      phone1 = "07811112222",
      price = "-15,000"
    )
    vm.saveOrConfirmStop(zeroStop)
    vm.saveOrConfirmStop(negStop)

    composeTestRule.setContent {
      androidx.compose.foundation.layout.Column {
        com.example.ui.components.DeliveryLocationCard(stop = zeroStop, onClick = {})
        com.example.ui.components.DeliveryLocationCard(stop = negStop, onClick = {})
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("prominent_price_price_zero", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("0", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("prominent_price_price_neg", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("-15,000", useUnmergedTree = true).assertIsDisplayed()

    val reopened = kotlinx.coroutines.runBlocking {
      DeliveryViewModel(context).loadSavedStopsSuspending()
    }
    assertEquals("0", reopened.first { it.id == "price_zero" }.price)
    assertEquals("-15,000", reopened.first { it.id == "price_neg" }.price)
  }

  @Test
  fun `external package and contents photos persist across edits category changes and ViewModel reopen`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    val imagesDir = java.io.File(context.filesDir, "order_images").apply { mkdirs() }
    val contentsFile = java.io.File(imagesDir, "test_contents_${System.currentTimeMillis()}.jpg").apply {
      writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
    }
    val externalFile = java.io.File(imagesDir, "test_external_${System.currentTimeMillis()}.jpg").apply {
      writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
    }
    val contentsUri = android.net.Uri.fromFile(contentsFile).toString()
    val externalUri = android.net.Uri.fromFile(externalFile).toString()

    vm.openNewOrderScreen()
    val newId = vm.uiState.value.selectedStop!!.id

    // Attach both cargo photos on brand new order before text edits
    vm.updateCapturedContentsPhoto(newId, contentsUri)
    vm.updateCapturedExternalPhoto(newId, externalUri)

    // Subsequent edits + confirm delivery must preserve both photos
    vm.updateOrderFields(newId, "777888", "07701234567", "", "قابل للكسر", "35,000")
    vm.updateStopStatus(newId, DeliveryStatus.DELAYED)
    val latestStop = vm.uiState.value.selectedStop!!
    kotlinx.coroutines.runBlocking {
      vm.saveStopSuspending(latestStop.copy(isNewOrder = false))
    }

    // Reopen fresh ViewModel simulating app close & reopen
    val reopenedVm = DeliveryViewModel(context)
    val persisted = kotlinx.coroutines.runBlocking {
      reopenedVm.loadSavedStopsSuspending()
    }.firstOrNull { it.id == newId }

    org.junit.Assert.assertNotNull(persisted)
    assertEquals(contentsUri, persisted!!.capturedContentsPhotoUri)
    assertEquals(externalUri, persisted.capturedExternalPhotoUri)
    assertTrue(persisted.hasContentsPhoto)
    assertTrue(persisted.hasExternalPhoto)
    assertTrue(contentsFile.exists() && contentsFile.length() > 0L)
    assertTrue(externalFile.exists() && externalFile.length() > 0L)
  }

  @Test
  fun `pending camera capture recovers external photo after simulated process death and ignores empty canceled file`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    val imagesDir = java.io.File(context.filesDir, "order_images").apply { mkdirs() }
    val pendingExternalFile = java.io.File(imagesDir, "hd_camera_recovery_${System.currentTimeMillis()}.jpg")
    val externalUri = android.net.Uri.fromFile(pendingExternalFile).toString()

    // Start a brand-new order and prepare camera capture BEFORE user enters anything else
    vm.openNewOrderScreen()
    val stopId = vm.uiState.value.selectedStop!!.id
    vm.preparePendingPhotoCapture(
      stopId = stopId,
      targetName = "EXTERNAL",
      outputUriString = externalUri,
      outputFilePath = pendingExternalFile.absolutePath
    )
    kotlinx.coroutines.runBlocking<Unit> {
      com.example.data.repository.DeliveryRepository.getInstance(context)?.flushPendingWrites()
    }

    // System camera writes JPEG bytes while app process is killed
    pendingExternalFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte()))

    // Fresh ViewModel after process death recovers the photo and reopens the order
    val restartedVm = DeliveryViewModel(context)
    val recovered = kotlinx.coroutines.runBlocking {
      restartedVm.loadSavedStopsSuspending()
    }.firstOrNull { it.id == stopId }

    org.junit.Assert.assertNotNull(recovered)
    assertEquals(externalUri, recovered!!.capturedExternalPhotoUri)
    assertTrue(recovered.hasExternalPhoto)
    assertEquals(stopId, restartedVm.uiState.value.selectedStop?.id)
    assertTrue(restartedVm.uiState.value.isDetailSheetOpen)
    org.junit.Assert.assertNull(com.example.data.local.SettingsPreferences(context).getPendingPhotoCapture())

    // Empty 0-byte canceled camera file should NOT be attached and should be cleaned up
    val emptyCanceledFile = java.io.File(imagesDir, "hd_camera_canceled_${System.currentTimeMillis()}.jpg").apply {
      createNewFile()
    }
    restartedVm.preparePendingPhotoCapture(
      stopId = stopId,
      targetName = "CONTENTS",
      outputUriString = android.net.Uri.fromFile(emptyCanceledFile).toString(),
      outputFilePath = emptyCanceledFile.absolutePath
    )
    val afterCanceledVm = DeliveryViewModel(context)
    val reloadedAfterCancel = kotlinx.coroutines.runBlocking {
      afterCanceledVm.loadSavedStopsSuspending()
    }.firstOrNull { it.id == stopId }

    org.junit.Assert.assertNull(reloadedAfterCancel!!.capturedContentsPhotoUri)
    org.junit.Assert.assertFalse(emptyCanceledFile.exists())
  }

  @Test
  fun `deleting contents or external photo requires confirmation and preserves photo on cancel`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)
    val initialStop = DeliveryStop(
      id = "photo_confirm_stop",
      orderNumber = "555111",
      orderCodeNumber = "555111",
      dateTime = "اليوم",
      title = "بغداد - المنصور",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = "IN_TRANSIT",
      categoryName = "قيد التوصيل",
      capturedContentsPhotoUri = "content://example/contents.jpg",
      capturedExternalPhotoUri = "content://example/external.jpg"
    )
    vm.saveOrConfirmStop(initialStop)
    vm.openDetailSheet(initialStop)

    composeTestRule.setContent {
      val state = vm.uiState.collectAsStateWithLifecycle().value
      val current = state.selectedStop
      if (current != null) {
        com.example.ui.components.CustomerOrderDetailsScreen(
          stop = current,
          onBack = { vm.closeDetailSheet() },
          onConfirmDelivery = { vm.saveOrConfirmStop(it) },
          onScreenshotChanged = { vm.updateScreenshotForStop(current.id, it) },
          onApplyOcr = { vm.applyOcrResult(current.id, it) },
          onSaveOrderFields = { code, p1, p2, notes, price ->
            vm.updateOrderFields(current.id, code, p1, p2, notes, price)
          },
          onIssuePhotoCaptured = { vm.updateCapturedIssuePhoto(current.id, it) },
          onContentsPhotoCaptured = { vm.updateCapturedContentsPhoto(current.id, it) },
          onExternalPhotoCaptured = { vm.updateCapturedExternalPhoto(current.id, it) }
        )
      }
    }
    composeTestRule.waitForIdle()

    // Single tap on delete external photo does NOT delete immediately; shows confirmation dialog
    composeTestRule.onNodeWithTag("btn_delete_external_photo").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("btn_cancel_delete_photo").assertIsDisplayed()
    assertEquals("content://example/external.jpg", vm.uiState.value.selectedStop?.capturedExternalPhotoUri)

    // Canceling keeps the photo intact
    composeTestRule.onNodeWithTag("btn_cancel_delete_photo").performClick()
    composeTestRule.waitForIdle()
    assertEquals("content://example/external.jpg", vm.uiState.value.selectedStop?.capturedExternalPhotoUri)

    // Confirming deletes only the external photo and leaves contents photo intact
    composeTestRule.onNodeWithTag("btn_delete_external_photo").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("btn_confirm_delete_photo").performClick()
    composeTestRule.waitForIdle()
    org.junit.Assert.assertNull(vm.uiState.value.selectedStop?.capturedExternalPhotoUri)
    assertEquals("content://example/contents.jpg", vm.uiState.value.selectedStop?.capturedContentsPhotoUri)
  }

  @Test
  fun `CallerAddressHelper finds registered customer address across phone formats and skips unregistered numbers`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val repo = com.example.data.repository.DeliveryRepository.getInstance(context)!!
    kotlinx.coroutines.runBlocking<Unit> {
      repo.saveCustomer(
        com.example.model.CustomerRecord(
          phone = "07710266414",
          originalPhone = "07710266414",
          writtenAddress = "بغداد - الكرادة",
          areaSubtitle = "قرب ساحة كهرمانة"
        )
      )
      repo.addSuspiciousCustomer("07710266414", "تكرار الإلغاء")
    }

    val matched = kotlinx.coroutines.runBlocking {
      com.example.util.CallerAddressHelper.lookupRegisteredCustomerAddress(context, "+964 771-026-6414")
    }
    org.junit.Assert.assertNotNull(matched)
    assertEquals("07710266414", matched!!.normalizedPhone)
    assertEquals(
      "بغداد - الكرادة - قرب ساحة كهرمانة",
      com.example.util.CallerAddressHelper.formatFullAddressLine(matched.customer)
    )
    org.junit.Assert.assertNotNull(matched.suspicious)

    val unregistered = kotlinx.coroutines.runBlocking {
      com.example.util.CallerAddressHelper.lookupRegisteredCustomerAddress(context, "07800001111")
    }
    org.junit.Assert.assertNull(unregistered)
  }

  @Test
  fun `Caller Address Bar activation state persists across ViewModel reopen and renders in SettingsScreen`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    vm.setCallerAddressBarEnabled(true)
    composeTestRule.waitForIdle()
    assertTrue(vm.uiState.value.isCallerAddressBarEnabled)
    assertTrue(com.example.data.local.SettingsPreferences(context).isCallerAddressBarEnabledSync())

    val reopenedVm = DeliveryViewModel(context)
    assertTrue(reopenedVm.uiState.value.isCallerAddressBarEnabled)

    var toggledValue: Boolean? = null
    composeTestRule.setContent {
      com.example.ui.components.SettingsScreen(
        isDarkMode = false,
        onToggleDarkMode = {},
        isCallerAddressBarEnabled = true,
        onToggleCallerAddressBar = { toggledValue = it },
        registeredCustomers = emptyList(),
        prefillPhone = "",
        onClearPrefillPhone = {},
        onRegisterCustomer = { _, _, _ -> },
        onLookupCustomerByPhone = { null },
        isFirestoreSyncing = false,
        onSyncFirestore = {}
      )
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("card_caller_address_bar_settings").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("switch_caller_address_bar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_caller_accessibility_permission").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_toggle_caller_address_bar").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(false, toggledValue)
  }

  @Test
  fun `CustomerRecord normalizes Arabic-Indic digits, double-zero +9640 prefix, and computes trailingPhoneKey`() {
    assertEquals("07710266414", CustomerRecord.normalizePhoneNumber("٠٧٧١٠٢٦٦٤١٤"))
    assertEquals("07710266414", CustomerRecord.normalizePhoneNumber("۰۷۷۱۰۲۶۶۴۱۴"))
    assertEquals("07710266414", CustomerRecord.normalizePhoneNumber("+96407710266414"))
    assertEquals("07710266414", CustomerRecord.normalizePhoneNumber("00964 0771 026 6414"))
    assertEquals("7710266414", CustomerRecord.trailingPhoneKey("+964 771 026 6414"))
    assertEquals("7710266414", CustomerRecord.trailingPhoneKey("07710266414"))
  }

  @Test
  fun `CallerAddressHelper extracts candidates from noisy call screens and resolves DeliveryStop fallback quickly`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val repo = com.example.data.repository.DeliveryRepository.getInstance(context)!!
    kotlinx.coroutines.runBlocking<Unit> {
      repo.saveStop(
        DeliveryStop(
          id = "stop_call_lookup",
          orderNumber = "908070",
          orderCodeNumber = "908070",
          dateTime = "الآن",
          title = "البصرة - العشار",
          subtitle = "قرب سوق البنات",
          phone1 = "07805554433",
          note = "",
          status = DeliveryStatus.IN_TRANSIT
        )
      )
    }

    val noisyCallScreen = """
      SIM 1 • جاري الاتصال بـ
      12:45
      زبون مسجل: +964 780 555 4433
      كتم الصوت   مكبر الصوت   إنهاء
    """.trimIndent()

    val candidates = com.example.util.CallerAddressHelper.extractCandidatePhonesFromText(noisyCallScreen)
    assertTrue(candidates.contains("07805554433"))
    org.junit.Assert.assertFalse(candidates.contains("1245"))

    val arabicCallScreen = "مكالمة واردة ٠٧٨٠٥٥٥٤٤٣٣ الآن"
    val arabicCandidates = com.example.util.CallerAddressHelper.extractCandidatePhonesFromText(arabicCallScreen)
    assertTrue(arabicCandidates.contains("07805554433"))

    val matched = kotlinx.coroutines.runBlocking {
      com.example.util.CallerAddressHelper.lookupFirstRegisteredCustomerInCandidates(context, arabicCandidates)
    }
    org.junit.Assert.assertNotNull(matched)
    assertEquals("07805554433", matched!!.normalizedPhone)
    assertEquals(
      "البصرة - العشار - قرب سوق البنات",
      com.example.util.CallerAddressHelper.formatFullAddressLine(matched.customer)
    )
  }

  @Test
  fun `CallerAddressHelper extracts WhatsApp bidi-wrapped phone numbers and detects incoming WhatsApp and SIM calls only`() {
    val whatsappIncomingText = "\u202A+964\u00A0771\u200E\u00A0026\u00A06414\u202C\nمكالمة صوتية عبر واتساب"
    val extracted = com.example.util.CallerAddressHelper.extractCandidatePhonesFromText(whatsappIncomingText)
    assertTrue(extracted.contains("07710266414"))

    assertTrue(com.example.util.CallerAddressHelper.containsIncomingOrActiveCallKeyword(whatsappIncomingText))
    assertTrue(com.example.util.CallerAddressHelper.containsIncomingOrActiveCallKeyword("WhatsApp voice call • 07710266414"))
    assertTrue(com.example.util.CallerAddressHelper.containsIncomingOrActiveCallKeyword("مكالمة واردة\nالرد   رفض"))
    org.junit.Assert.assertFalse(
      com.example.util.CallerAddressHelper.containsIncomingOrActiveCallKeyword("مرحباً أخي، هذا رقمي 07710266414 للطلب الجديد")
    )

    val callNotification = androidx.core.app.NotificationCompat.Builder(
      ApplicationProvider.getApplicationContext(),
      "test_channel"
    )
      .setCategory(android.app.Notification.CATEGORY_CALL)
      .setContentTitle("+964 771 026 6414")
      .setContentText("Incoming WhatsApp voice call")
      .build()

    val combined = com.example.util.CallerAddressHelper.extractNotificationCombinedText(
      notification = callNotification,
      eventTexts = null
    )
    assertTrue(
      com.example.util.CallerAddressHelper.isCallNotification(
        packageName = "com.whatsapp",
        notification = callNotification,
        combinedText = combined,
        isActiveCall = false
      )
    )
    assertTrue(
      com.example.util.CallerAddressHelper.extractCandidatePhonesFromText(combined).contains("07710266414")
    )
  }

  @Test
  fun `CallAddressOverlayService stays persistent after call ends until manually dismissed`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val shadowApp = Shadows.shadowOf(context)
    kotlinx.coroutines.runBlocking<Unit> {
      com.example.data.local.SettingsPreferences(context).setCallerAddressBarEnabled(true)
    }

    val receiver = com.example.service.CallStateReceiver()
    val idleIntent = android.content.Intent(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
      putExtra(
        android.telephony.TelephonyManager.EXTRA_STATE,
        android.telephony.TelephonyManager.EXTRA_STATE_IDLE
      )
    }
    receiver.onReceive(context, idleIntent)
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    // Receiving PHONE_STATE IDLE must never enqueue any dismiss/stop command for CallAddressOverlayService
    val stoppedService = shadowApp.nextStoppedService
    val startedService = shadowApp.nextStartedService
    assertNull(stoppedService)
    assertNull(startedService)
  }

  @Test
  fun `Dialer Recents and WhatsApp chat history are rejected so post-call screens never show someone else`() {
    val dialerRecentsScreen = """
      سجل المكالمات
      مكالمة واردة • 07710266414
      مكالمة صادرة • 07805554433
      المفضلة   جهات الاتصال
    """.trimIndent()
    val candidates = com.example.util.CallerAddressHelper.extractCandidatePhonesFromText(dialerRecentsScreen)
    assertTrue(
      com.example.util.CallerAddressHelper.looksLikeCallHistoryOrListScreen(
        dialerRecentsScreen,
        candidates
      )
    )
    org.junit.Assert.assertFalse(
      com.example.util.CallerAddressHelper.containsIncomingOrActiveCallKeyword(dialerRecentsScreen)
    )

    val threeNumbersScreen = "07710266414\n07805554433\n07721112233"
    val multiCandidates = com.example.util.CallerAddressHelper.extractCandidatePhonesFromText(threeNumbersScreen)
    assertTrue(
      com.example.util.CallerAddressHelper.looksLikeCallHistoryOrListScreen(
        threeNumbersScreen,
        multiCandidates
      )
    )
  }

  @Test
  fun `CallAddressOverlayService refuses overwrite from passive scans and updates in-place without hiding when explicitly replaced`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)

    val controller = org.robolectric.Robolectric.buildService(com.example.service.CallAddressOverlayService::class.java)
      .create()
    val service = controller.get()

    val customerAIntent = android.content.Intent(context, com.example.service.CallAddressOverlayService::class.java).apply {
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_PHONE, "07710266414")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_NORMALIZED_PHONE, "07710266414")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_ADDRESS, "بغداد - الكرادة")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_IS_PREVIEW, false)
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_ALLOW_REPLACE_EXISTING, true)
    }
    service.onStartCommand(customerAIntent, 0, 1)
    assertTrue(com.example.service.CallAddressOverlayService.isShowingFlow.value)

    val addressViewField = com.example.service.CallAddressOverlayService::class.java.getDeclaredField("addressTextView")
    addressViewField.isAccessible = true
    val overlayViewField = com.example.service.CallAddressOverlayService::class.java.getDeclaredField("overlayView")
    overlayViewField.isAccessible = true
    val initialOverlayInstance = overlayViewField.get(service)

    assertEquals("بغداد - الكرادة", (addressViewField.get(service) as android.widget.TextView).text.toString())

    // Passive scan (allowReplaceExisting = false) for a different person must NOT overwrite Customer A
    val passiveOtherIntent = android.content.Intent(context, com.example.service.CallAddressOverlayService::class.java).apply {
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_PHONE, "07805554433")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_NORMALIZED_PHONE, "07805554433")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_ADDRESS, "البصرة - العشار")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_IS_PREVIEW, false)
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_ALLOW_REPLACE_EXISTING, false)
    }
    service.onStartCommand(passiveOtherIntent, 0, 2)
    assertEquals("بغداد - الكرادة", (addressViewField.get(service) as android.widget.TextView).text.toString())
    assertTrue(initialOverlayInstance === overlayViewField.get(service))

    // Explicit genuine new call (allowReplaceExisting = true) updates IN PLACE without removing/recreating window
    val explicitNewCallIntent = android.content.Intent(context, com.example.service.CallAddressOverlayService::class.java).apply {
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_PHONE, "07805554433")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_NORMALIZED_PHONE, "07805554433")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_ADDRESS, "البصرة - العشار")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_IS_PREVIEW, false)
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_ALLOW_REPLACE_EXISTING, true)
    }
    service.onStartCommand(explicitNewCallIntent, 0, 3)
    assertEquals("البصرة - العشار", (addressViewField.get(service) as android.widget.TextView).text.toString())
    assertTrue(initialOverlayInstance === overlayViewField.get(service))

    controller.destroy()
    org.junit.Assert.assertFalse(com.example.service.CallAddressOverlayService.isShowingFlow.value)
  }

  @Test
  fun `Delivered status and external package photo persist across app exit, pause-stop flush, and fresh reopen`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val imagesDir = java.io.File(context.filesDir, "order_images").apply { mkdirs() }
    val externalPhotoFile = java.io.File(imagesDir, "hd_camera_external_exit_test.jpg").apply {
      writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x01, 0x02))
    }
    val externalUriStr = "file://${externalPhotoFile.absolutePath}"

    val vm1 = DeliveryViewModel(context)
    val order = DeliveryStop(
      id = "ord_exit_delivered_photo",
      orderNumber = "778899",
      orderCodeNumber = "778899",
      price = "30000",
      dateTime = "الآن",
      title = "بغداد - المنصور",
      subtitle = "شارع 14 رمضان",
      note = "قيد التوصيل",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = DeliveryStatus.IN_TRANSIT.name,
      categoryName = "قيد التوصيل",
      phone1 = "07712345678"
    )
    vm1.addNewStop(order)
    vm1.openDetailSheet(order)
    vm1.updateCapturedExternalPhoto(order.id, externalUriStr)
    val deliveredCategory = DeliveryCategory.DEFAULT_CATEGORIES.first { it.id == "DELIVERED" }
    vm1.updateStopCategory(order.id, deliveredCategory)

    // Simulate leaving the app (onPause / onStop -> persistAndFlushBlocking) before process kill/reopen
    vm1.persistAndFlushBlocking()

    // Simulate fresh app reopen with a brand-new ViewModel reading directly from Room disk DB
    val vm2 = DeliveryViewModel(context)
    val reloadedStops = kotlinx.coroutines.runBlocking {
      vm2.loadSavedStopsSuspending()
    }
    val reloadedOrder = reloadedStops.firstOrNull { it.id == order.id }
    org.junit.Assert.assertNotNull(reloadedOrder)
    assertEquals(DeliveryStatus.DELIVERED, reloadedOrder!!.status)
    assertEquals("DELIVERED", reloadedOrder.categoryId)
    assertEquals("تم التسليم", reloadedOrder.categoryName)
    assertEquals(externalUriStr, reloadedOrder.capturedExternalPhotoUri)
    assertTrue(reloadedOrder.hasExternalPhoto)
    assertTrue(externalPhotoFile.exists() && externalPhotoFile.length() > 0L)
  }

  @Test
  fun `Stale confirm delivery snapshot never erases external or contents photos already saved on order`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)
    val order = DeliveryStop(
      id = "ord_stale_confirm_guard",
      orderNumber = "554433",
      orderCodeNumber = "554433",
      dateTime = "الآن",
      title = "أربيل - عنكاوا",
      subtitle = "",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = DeliveryStatus.IN_TRANSIT.name,
      categoryName = "قيد التوصيل"
    )
    vm.addNewStop(order)
    vm.openDetailSheet(order)
    vm.updateCapturedExternalPhoto(order.id, "content://example/external_hd.jpg")
    vm.updateCapturedContentsPhoto(order.id, "content://example/contents_hd.jpg")

    // Confirm using a stale snapshot of 'order' that still had null photo URIs
    val staleCopyMarkedDelivered = order.copy(
      status = DeliveryStatus.DELIVERED,
      categoryId = DeliveryStatus.DELIVERED.name,
      categoryName = "تم التسليم",
      capturedExternalPhotoUri = null,
      capturedContentsPhotoUri = null
    )
    vm.saveOrConfirmStop(staleCopyMarkedDelivered)
    vm.persistAndFlushBlocking()

    val current = vm.uiState.value.stops.first { it.id == order.id }
    assertEquals(DeliveryStatus.DELIVERED, current.status)
    assertEquals("DELIVERED", current.categoryId)
    assertEquals("content://example/external_hd.jpg", current.capturedExternalPhotoUri)
    assertEquals("content://example/contents_hd.jpg", current.capturedContentsPhotoUri)
  }

  @Test
  fun `OcrHelper accuracy helpers repair numeric lookalikes and process dark screenshots for contrast`() {
    val repaired = com.example.util.OcrHelper.repairNumericLookalikes(
      "IQD 38,OOO  O7875160858  17l348608  ORDER"
    )
    assertTrue(repaired.contains("IQD"))
    assertTrue(repaired.contains("ORDER"))
    assertTrue(repaired.contains("38,000"))
    assertTrue(repaired.contains("07875160858"))
    assertTrue(repaired.contains("171348608"))

    val darkBmp = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
    darkBmp.eraseColor(android.graphics.Color.rgb(18, 18, 22))
    val contrastFromDark = com.example.util.OcrHelper.createHighContrastOcrBitmap(darkBmp)
    org.junit.Assert.assertNotNull(contrastFromDark)
    val centerPixel = contrastFromDark!!.getPixel(60, 60)
    assertTrue(android.graphics.Color.red(centerPixel) > 180)
    contrastFromDark.recycle()
    darkBmp.recycle()

    val sourceBmp = android.graphics.Bitmap.createBitmap(600, 1200, android.graphics.Bitmap.Config.ARGB_8888)
    val upperHeaderCrop = com.example.util.OcrHelper.createUpscaledUpperHeaderCrop(sourceBmp)
    org.junit.Assert.assertNotNull(upperHeaderCrop)
    assertTrue(upperHeaderCrop!!.width >= 600)
    assertTrue(upperHeaderCrop.height > (1200 * 0.56f).toInt())
    upperHeaderCrop.recycle()
    sourceBmp.recycle()
  }

  @Test
  fun `MainActivity and CustomerOrderDetailsScreen omit removed UI rectangles while preserving core interaction`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    val delayedStop = DeliveryStop(
      id = "ui_check_1",
      orderNumber = "171348608",
      orderCodeNumber = "171348608",
      dateTime = "اليوم",
      title = "حي القاهرة",
      subtitle = "الجمهورية",
      note = "",
      status = DeliveryStatus.DELAYED,
      categoryId = "DELAYED",
      categoryName = "مؤجل",
      phone1 = "07778082045",
      price = "38,000"
    )
    vm.saveOrConfirmStop(delayedStop)
    vm.setCategoryFilter("DELAYED")

    val showDetails = androidx.compose.runtime.mutableStateOf(false)
    composeTestRule.setContent {
      if (!showDetails.value) {
        DeliveryAddressesScreen(viewModel = vm)
      } else {
        com.example.ui.components.CustomerOrderDetailsScreen(
          stop = delayedStop.copy(screenshotUriString = null, isOcrProcessed = false),
          onBack = {},
          onConfirmDelivery = {},
          onScreenshotChanged = {},
          onApplyOcr = {},
          onSaveOrderFields = { _, _, _, _, _ -> },
          onIssuePhotoCaptured = {}
        )
      }
    }
    composeTestRule.waitForIdle()

    // Top title + selection mode button and active filter banner must be removed
    composeTestRule.onNodeWithText("عناوين التوصيل").assertDoesNotExist()
    composeTestRule.onNodeWithTag("btn_enter_selection_mode").assertDoesNotExist()
    composeTestRule.onNodeWithTag("banner_active_filter").assertDoesNotExist()
    composeTestRule.onNodeWithTag("btn_clear_filter").assertDoesNotExist()

    // Tabs and filter chips remain present and functional
    composeTestRule.onNodeWithTag("tab_orders").assertIsDisplayed()
    composeTestRule.onNodeWithTag("tab_settings").assertIsDisplayed()
    composeTestRule.onNodeWithTag("chip_filter_all").performClick()
    composeTestRule.waitForIdle()
    assertNull(vm.uiState.value.selectedCategoryId)

    // Verify Settings shows Background Keep-Alive card
    composeTestRule.onNodeWithTag("tab_settings").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("card_background_keep_alive").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("switch_background_keep_alive").assertIsDisplayed()

    // Switch to CustomerOrderDetailsScreen and verify screenshot header bar, OCR button/banner, and coordinates UI are removed
    showDetails.value = true
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("لقطة الشاشة من تطبيق العمل").assertDoesNotExist()
    composeTestRule.onNodeWithTag("btn_choose_screenshot").assertDoesNotExist()
    composeTestRule.onNodeWithTag("btn_run_ocr").assertDoesNotExist()
    composeTestRule.onNodeWithTag("btn_save_real_location").assertDoesNotExist()
    composeTestRule.onNodeWithText("حالة الإحداثيات").assertDoesNotExist()
    composeTestRule.onNodeWithText("حفظ موقعي كموقع للزبون").assertDoesNotExist()
    composeTestRule.onNodeWithTag("placeholder_empty_screenshot").assertIsDisplayed()
  }

  @Test
  fun `BackgroundKeepAliveService preferences BootCompletedReceiver and ActiveCallerBarState persist across reopen`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val prefs = com.example.data.local.SettingsPreferences(context)

    // Background Keep-Alive enabled by default and toggleable via DeliveryViewModel
    assertTrue(prefs.isBackgroundKeepAliveEnabledSync())
    val vm = DeliveryViewModel(context)
    assertTrue(vm.uiState.value.isBackgroundKeepAliveEnabled)

    vm.setBackgroundKeepAliveEnabled(false)
    org.junit.Assert.assertFalse(prefs.isBackgroundKeepAliveEnabledSync())
    vm.setBackgroundKeepAliveEnabled(true)
    assertTrue(prefs.isBackgroundKeepAliveEnabledSync())

    // ActiveCallerBarState saved in foreground stays persisted until explicitly dismissed
    org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
    val controller = org.robolectric.Robolectric.buildService(com.example.service.CallAddressOverlayService::class.java)
      .create()
    val service = controller.get()

    val showIntent = android.content.Intent(context, com.example.service.CallAddressOverlayService::class.java).apply {
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_PHONE, "07710266414")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_NORMALIZED_PHONE, "07710266414")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_ADDRESS, "بغداد - الكرادة")
      putExtra(com.example.service.CallAddressOverlayService.EXTRA_IS_PREVIEW, false)
    }
    service.onStartCommand(showIntent, 0, 1)

    val savedBar = prefs.getActiveCallerBar()
    org.junit.Assert.assertNotNull(savedBar)
    assertEquals("بغداد - الكرادة", savedBar!!.address)

    // Verify BootCompletedReceiver starts keep-alive + quick add + saved caller bar on ACTION_BOOT_COMPLETED
    prefs.setCallerAddressBarEnabledSync(true)
    prefs.setFloatingQuickAddEnabledSync(true)
    val shadowApp = Shadows.shadowOf(context)
    shadowApp.clearStartedServices()

    val bootReceiver = com.example.service.BootCompletedReceiver()
    bootReceiver.onReceive(context, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    val startedClasses = mutableListOf<String>()
    while (true) {
      val started = shadowApp.nextStartedService ?: break
      startedClasses.add(started.component?.className.orEmpty())
    }
    assertTrue(startedClasses.any { it.endsWith("BackgroundKeepAliveService") })
    assertTrue(startedClasses.any { it.endsWith("FloatingQuickAddService") })
    assertTrue(startedClasses.any { it.endsWith("CallAddressOverlayService") })

    // Explicit dismiss clears persisted ActiveCallerBarState
    com.example.service.CallAddressOverlayService.dismiss(context)
    assertNull(prefs.getActiveCallerBar())
    controller.destroy()
  }

  @Test
  fun `top-left RefreshSyncButton auto-refreshes and saves address edits category moves and manual tap`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)

    val order = DeliveryStop(
      id = "refresh_test_order_1",
      orderNumber = "900123",
      orderCodeNumber = "900123",
      dateTime = "الآن",
      title = "العنوان القديم",
      subtitle = "الحي القديم",
      note = "",
      status = DeliveryStatus.IN_TRANSIT,
      categoryId = DeliveryStatus.IN_TRANSIT.name,
      categoryName = "قيد التوصيل",
      phone1 = "07719998877",
      price = "45,000"
    )
    vm.saveOrConfirmStop(order)
    val genAfterCreate = vm.uiState.value.refreshGeneration
    assertTrue(genAfterCreate >= 1)

    // 1. Rewriting customer address triggers auto-refresh & saves updated address
    vm.updateCustomerAddress(order.id, "الكرادة - شارع 62", "قرب الساحة")
    val genAfterAddressEdit = vm.uiState.value.refreshGeneration
    assertTrue(genAfterAddressEdit > genAfterCreate)

    // 2. Moving order between category lists triggers auto-refresh & saves category
    val delayedCategory = vm.uiState.value.categories.first { it.id == DeliveryStatus.DELAYED.name }
    vm.updateStopCategory(order.id, delayedCategory)
    val genAfterCategoryMove = vm.uiState.value.refreshGeneration
    assertTrue(genAfterCategoryMove > genAfterAddressEdit)

    // 3. Render both main screen and order details screen to verify top-left refresh buttons work
    val showDetails = androidx.compose.runtime.mutableStateOf(false)
    composeTestRule.setContent {
      androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl
      ) {
        if (!showDetails.value) {
          DeliveryAddressesScreen(viewModel = vm)
        } else {
          val latest = vm.uiState.value.stops.first { it.id == order.id }
          com.example.ui.components.CustomerOrderDetailsScreen(
            stop = latest,
            onBack = {},
            onConfirmDelivery = {},
            onScreenshotChanged = {},
            onApplyOcr = {},
            onSaveOrderFields = { _, _, _, _, _ -> },
            onIssuePhotoCaptured = {},
            isAppRefreshing = vm.uiState.value.isAppRefreshing,
            refreshGeneration = vm.uiState.value.refreshGeneration,
            onRefreshAndSave = { vm.refreshAndSaveApp(manual = true) }
          )
        }
      }
    }
    composeTestRule.waitForIdle()

    // Main screen refresh button exists and tapping triggers manual refresh & save
    composeTestRule.onNodeWithTag("btn_refresh_app").assertIsDisplayed()
    val genBeforeManualTap = vm.uiState.value.refreshGeneration
    composeTestRule.onNodeWithTag("btn_refresh_app").performClick()
    composeTestRule.waitForIdle()
    assertTrue(vm.uiState.value.refreshGeneration > genBeforeManualTap)

    // Details screen refresh button also exists and is clickable
    showDetails.value = true
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("btn_refresh_order_details").assertIsDisplayed()
    val genBeforeDetailsTap = vm.uiState.value.refreshGeneration
    composeTestRule.onNodeWithTag("btn_refresh_order_details").performClick()
    composeTestRule.waitForIdle()
    assertTrue(vm.uiState.value.refreshGeneration > genBeforeDetailsTap)

    // Flush & reopen a fresh ViewModel to prove latest address & category persisted durably
    vm.persistAndFlushBlocking()
    val reopenedVm = DeliveryViewModel(context)
    val reloaded = kotlinx.coroutines.runBlocking { reopenedVm.loadSavedStopsSuspending() }
    val persisted = reloaded.firstOrNull { it.id == order.id }
    org.junit.Assert.assertNotNull(persisted)
    assertEquals("الكرادة - شارع 62", persisted!!.title)
    assertEquals("قرب الساحة", persisted.subtitle)
    assertEquals(DeliveryStatus.DELAYED.name, persisted.categoryId)
    assertEquals(DeliveryStatus.DELAYED, persisted.status)
  }

  @Test
  fun `AppUpdateChecker parses GitHub and JSON releases compares versions and renders in SettingsScreen`() {
    // 1. Endpoint normalization supports GitHub repo links, owner/repo shorthand, HTTPS JSON, and rejects unsafe schemes
    assertEquals(
      "https://api.github.com/repos/myuser/masar-app/releases/latest",
      com.example.util.AppUpdateChecker.normalizeUpdateEndpoint("https://github.com/myuser/masar-app")
    )
    assertEquals(
      "https://api.github.com/repos/myuser/masar-app/releases/latest",
      com.example.util.AppUpdateChecker.normalizeUpdateEndpoint("myuser/masar-app")
    )
    assertEquals(
      "https://example.com/releases/update.json",
      com.example.util.AppUpdateChecker.normalizeUpdateEndpoint("https://example.com/releases/update.json")
    )
    assertNull(com.example.util.AppUpdateChecker.normalizeUpdateEndpoint("http://insecure.example.com/update.json"))
    assertNull(com.example.util.AppUpdateChecker.normalizeUpdateEndpoint("javascript:alert(1)"))

    // 2. Custom JSON manifest parsing & version comparison
    val manifestJson = """
      {
        "versionCode": 2,
        "versionName": "1.1",
        "releaseNotes": "إصلاح مشكلة التصنيفات وتحسين الأداء",
        "downloadUrl": "https://example.com/releases/v1.1"
      }
    """.trimIndent()
    val parsedManifest = com.example.util.AppUpdateChecker.parseReleaseJson(manifestJson)
    org.junit.Assert.assertNotNull(parsedManifest)
    assertEquals(2, parsedManifest!!.versionCode)
    assertEquals("1.1", parsedManifest.versionName)
    assertEquals("https://example.com/releases/v1.1", parsedManifest.downloadUrl)
    assertTrue(com.example.util.AppUpdateChecker.isNewerVersion(1, "1.0", parsedManifest))
    org.junit.Assert.assertFalse(com.example.util.AppUpdateChecker.isNewerVersion(2, "1.1", parsedManifest))

    // 3. GitHub Releases API parsing & semantic version comparison
    val githubJson = """
      {
        "tag_name": "v1.10.0",
        "body": "تحديث جديد مع تحسين دقة OCR",
        "html_url": "https://github.com/myuser/masar-app/releases/tag/v1.10.0"
      }
    """.trimIndent()
    val parsedGh = com.example.util.AppUpdateChecker.parseReleaseJson(githubJson)
    org.junit.Assert.assertNotNull(parsedGh)
    assertEquals("1.10.0", parsedGh!!.versionName)
    assertTrue(com.example.util.AppUpdateChecker.isNewerVersion(1, "1.2.0", parsedGh))
    org.junit.Assert.assertFalse(com.example.util.AppUpdateChecker.isNewerVersion(1, "1.10.0", parsedGh))

    // 4. ViewModel persistence & SettingsScreen bottom card UI rendering
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = DeliveryViewModel(context)
    vm.saveUpdateSourceUrl("https://github.com/myuser/masar-app")
    assertEquals("https://github.com/myuser/masar-app", vm.uiState.value.updateSourceUrl)

    val updateState = androidx.compose.runtime.mutableStateOf<com.example.util.AppUpdateCheckState>(
      com.example.util.AppUpdateCheckState.UpdateAvailable(
        currentVersionName = "1.0",
        release = parsedManifest,
        checkedAt = "2026-11-19 10:00 AM"
      )
    )
    composeTestRule.setContent {
      com.example.ui.components.SettingsScreen(
        isDarkMode = false,
        onToggleDarkMode = {},
        registeredCustomers = emptyList(),
        prefillPhone = "",
        onClearPrefillPhone = {},
        onRegisterCustomer = { _, _, _ -> },
        onLookupCustomerByPhone = { null },
        isFirestoreSyncing = false,
        onSyncFirestore = {},
        updateSourceUrl = vm.uiState.value.updateSourceUrl,
        lastUpdateCheckTime = "2026-11-19 10:00 AM",
        appUpdateState = updateState.value,
        onSaveUpdateSourceUrl = { vm.saveUpdateSourceUrl(it) },
        onCheckForAppUpdates = {}
      )
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("card_app_updates_settings").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("text_current_app_version").assertIsDisplayed()
    composeTestRule.onNodeWithTag("status_update_available").assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_open_update_release").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_check_app_updates").performScrollTo().assertIsDisplayed()
  }
}





