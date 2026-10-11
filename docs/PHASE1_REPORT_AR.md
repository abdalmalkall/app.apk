# تقرير المرحلة الأولى — العملاء والموردون والديون

تاريخ التنفيذ: 10 أكتوبر 2026.

1. **ما كان موجوداً:** جداول Customer/Supplier ومدفوعاتهما، معاملات DEBT، ترقيم فواتير العملاء، وربط الفواتير بالأطراف والحسابات عبر Room. كان هناك عمل محلي سابق غير محفوظ في Git على ملفات العملاء والموردين وحقول بياناتهم وترحيل 11→12. تمت متابعة هذا العمل، دون إنشاء نظام أطراف موازٍ.
2. **ما أُضيف واستُكمل:** صفحات القائمة والتفاصيل؛ البحث بالاسم والهاتف؛ إجمالي المستحقات؛ فلاتر الكل/غير المسددين/المسددين؛ ترتيب آخر معاملة/أكبر دين/الاسم؛ إنشاء وتعديل بيانات العميل والمورد؛ إضافة فاتورة من العميل وشراء من المورد؛ آخر خمس مشتريات؛ نموذج الدفعة مع الحساب والتاريخ والطريقة والملاحظات؛ رسائل الحفظ والأخطاء وحالة التحميل؛ كشف الحساب حسب الفترة.
3. **الملفات:** القائمة أدناه هي الملفات التي تغيّرت مقارنةً بنقطة الرجوع قبل هذه الجلسة، وليست مجرد مقارنة مع HEAD التي ستشمل أيضاً عمل المستخدم السابق. بقيت تعديلات المستخدم الأخرى كما كانت.
4. **ربط العملاء بالفواتير:** نعم، عبر customer_id. فتح إضافة فاتورة من العميل يحمّل اسمه وهاتفه ومعرّفه ويختار نوع DEBT. تعديل بيانات العميل يحافظ على تاريخ إنشائه. الفواتير والمدفوعات تستخدم البيانات الفعلية.
5. **ربط الموردين بالمشتريات:** نعم، عبر supplier_id. المشتريات النقدية EXPENSE محسوبة مسددة، والمشتريات الآجلة DEBT لها دفعات مستقلة. آخر المشتريات مأخوذة من المعاملات الفعلية.
6. **الدفعات الجزئية والكاملة:** نعم. كل دفعة مرتبطة بفاتورة واحدة؛ لتوزيع الدفع على أكثر من فاتورة تُسجّل دفعة لكل فاتورة. تجاوز المتبقي يُرفض بوضوح، ولا يُنشئ رصيداً دائناً أو عملية بيع/شراء إضافية. طريقة الدفع والملاحظات محفوظتان في وصف الدفعة.
7. **الأرصدة:** الحسابات الأساسية للدفعات والفواتير وكشوف الحساب تستخدم BigDecimal. أضيف amount_decimal إلى الحسابات والمعاملات ومدفوعات العملاء والموردين كمصدر عشري دقيق، مع الإبقاء على أعمدة REAL القديمة وواجهات Double للتوافق والعرض. ترحيل 12→13 لا يدوّر الأرصدة القديمة ولا يغيّر قيمها الأصلية. مبالغ الدفعات الجديدة تقبل حتى ثلاث منازل عشرية. العمليات المالية مترابطة وذرية؛ معرف الدفعة ثابت لمنع إعادة تطبيقها؛ حذفها يعكس أثرها المالي مرة واحدة. لا يمكن حذف فاتورة لها دفعات أو تغيير هويتها المالية قبل عكس الدفعات.
8. **كشوف الحساب:** حقيقية، وتتضمن الاسم والتواصل والعنوان والفترة والرصيد الافتتاحي وكل فاتورة/دفعة ومرجعها ووصفها والرصيد الجاري والختامي. ترتيبها زمني مع ترتيب ثابت عند تساوي الزمن، والفاتورة تسبق دفعتها في الزمن نفسه. نموذج AccountStatement منفصل عن Compose ومجهز للتصدير والطباعة لاحقاً.
9. **الأزرار:** إجراءات الإضافة والتعديل والحفظ والدفعات والحذف مع عكس الرصيد وإضافة الفواتير والمشتريات والبحث والفلاتر والترتيب وكشف الحساب والرجوع مربوطة بكود فعلي. اختبارات ViewModel تغطي إجراءات رئيسية، لكن لم يُجر اختبار نقر شامل للواجهات على هاتف. زر كشف العميل يعرض الكشف؛ مشاركة الفاتورة تستخدم ACTION_SEND. لا يوجد زر طباعة وهمي: طباعة كشف الحساب وPDF غير مربوطتين بعد، والواجهة توضح ذلك.
10. **APK:** نجح :app:assembleDebug. الملف: app/build/outputs/apk/debug/app-debug.apk. إصدار Debug ومعرّف التطبيق com.naveenapps.expensemanager.debug.
11. **الاختبارات:** نجحت 187 حالة دون فشل أو تخطٍّ: core:data = 112، feature:transaction = 26، core:domain = 49. تشمل قاعدة Room فعلية، المثالين المحاسبيين، التسديد الكامل، رفض القيم غير الصحيحة والدفعات الزائدة، تكرار الحفظ وإعادة إرسال نفس معرف الدفعة، عكس المدفوعات، حماية الفواتير المرتبطة بمدفوعات، التراجع عند فشل تعديل معاملة، منع استبدال فاتورة برقم مكرر، الرصيد العشري عند مبالغ تتجاوز دقة Double، إنشاء وتعديل بيانات الأطراف والبحث، وكشف الحساب ورصيد افتتاح الفترة. اختبارات الترحيل تفتح قواعد مبنية من مخططي 11 و12 وتحدّثهما إلى 13 وتتحقق من مخطط Room والسجلات والأرصدة والتواريخ المخزنة.
12. **اختبار الهاتف المطلوب:** العربية وRTL، ظهور لوحة المفاتيح وتمرير النماذج، اختيار الحساب والتاريخ، أزرار الرجوع، الضغط المتكرر أثناء الحفظ، تحديث القوائم بعد العودة من إنشاء فاتورة/شراء، مشاركة الفاتورة عبر تطبيق فعلي، وتثبيت التحديث على نسخة فعلية تحتوي بيانات. لم تُشغّل اختبارات instrumentation على جهاز أو محاكي في هذه الجلسة؛ اختبارات Room والترحيل المنفذة كانت عبر Robolectric.

## المثالان المحاسبيان

- أحمد: فاتورة 100، دفعة 30 ثم 20 → المدفوع 50، المتبقي 50، وزيادة الصندوق 50 فقط. ثم دفعة 50 تسدد الفاتورة بالكامل، دون فاتورة بيع إضافية.
- محمد: شراء 200، دفعة 80 ثم 50 → المدفوع 130، المتبقي 70، وانخفاض الصندوق 130 فقط، دون شراء إضافي.

## أوامر التحقق

```sh
./gradlew :core:data:testDebugUnitTest :feature:transaction:testDebugUnitTest :core:domain:testDebugUnitTest :app:assembleDebug --console=plain
git diff --check
```

نتيجة Gradle النهائية: BUILD SUCCESSFUL. سجل التشغيل: /tmp/phase1-release-check.log.

تقارير الاختبارات:

- core/data/build/reports/tests/testDebugUnitTest/index.html
- feature/transaction/build/reports/tests/testDebugUnitTest/index.html
- core/domain/build/reports/tests/testDebugUnitTest/index.html

## نقطة الرجوع والنطاق

حُفظت الملفات المحلية المعدلة والجديدة قبل البدء في /tmp/expensemanager-phase1-before.tar.gz، مع فرق Git ثنائي في /tmp/expensemanager-phase1-before.patch. لم تُحذف تعديلات المستخدم ولم يُنشأ commit أو تُستبدل حالة Git. هذه نسخة رجوع للكود، وليست تطويراً لميزة النسخ الاحتياطي داخل التطبيق. لم تُعدّل ملفات Backup أو Google Drive، ولم تُضف ميزات مخزون أو باركود.

## الملفات المعدلة أو المضافة في هذه الجلسة

- `core/data/build.gradle.kts`
- `core/data/src/main/kotlin/com/naveenapps/expensemanager/core/data/di/KoinRepositoryModule.kt`
- `core/data/src/main/kotlin/com/naveenapps/expensemanager/core/data/mappers/AccountMappers.kt`
- `core/data/src/main/kotlin/com/naveenapps/expensemanager/core/data/mappers/CustomerPaymentMappers.kt`
- `core/data/src/main/kotlin/com/naveenapps/expensemanager/core/data/mappers/SupplierMappers.kt`
- `core/data/src/main/kotlin/com/naveenapps/expensemanager/core/data/mappers/TransactionMappers.kt`
- `core/data/src/main/kotlin/com/naveenapps/expensemanager/core/data/repository/CustomerPaymentRepositoryImpl.kt`
- `core/data/src/main/kotlin/com/naveenapps/expensemanager/core/data/repository/CustomerRepositoryImpl.kt`
- `core/data/src/main/kotlin/com/naveenapps/expensemanager/core/data/repository/ShareRepositoryImpl.kt`
- `core/data/src/main/kotlin/com/naveenapps/expensemanager/core/data/repository/SupplierRepositoryImpl.kt`
- `core/data/src/main/kotlin/com/naveenapps/expensemanager/core/data/repository/TransactionRepositoryImpl.kt`
- `core/data/src/test/kotlin/com/naveenapps/expensemanager/core/data/repository/CurrencyRepositoryImplTest.kt`
- `core/data/src/test/kotlin/com/naveenapps/expensemanager/core/data/repository/CustomerRepositoryImplTest.kt`
- `core/data/src/test/kotlin/com/naveenapps/expensemanager/core/data/repository/LedgerAccountingTest.kt`
- `core/data/src/test/kotlin/com/naveenapps/expensemanager/core/data/repository/LedgerMigrationTest.kt`
- `core/data/src/test/kotlin/com/naveenapps/expensemanager/core/data/repository/TransactionRepositoryImplTest.kt`
- `core/database/schemas/com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase/13.json`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/DatabaseMigrations.kt`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/ExpenseManagerDatabase.kt`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/dao/CustomerPaymentDao.kt`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/dao/SupplierPaymentDao.kt`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/dao/TransactionDao.kt`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/di/KoinDatabaseModule.kt`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/entity/AccountEntity.kt`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/entity/CustomerPaymentEntity.kt`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/entity/ExactAccountBalance.kt`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/entity/SupplierPaymentEntity.kt`
- `core/database/src/main/kotlin/com/naveenapps/expensemanager/core/database/entity/TransactionEntity.kt`
- `core/model/src/main/kotlin/com/naveenapps/expensemanager/core/model/Account.kt`
- `core/model/src/main/kotlin/com/naveenapps/expensemanager/core/model/AccountStatement.kt`
- `core/model/src/main/kotlin/com/naveenapps/expensemanager/core/model/Amount.kt`
- `core/model/src/main/kotlin/com/naveenapps/expensemanager/core/model/CustomerPayment.kt`
- `core/model/src/main/kotlin/com/naveenapps/expensemanager/core/model/LedgerMoney.kt`
- `core/model/src/main/kotlin/com/naveenapps/expensemanager/core/model/SupplierPayment.kt`
- `core/navigation/src/main/kotlin/com/naveenapps/expensemanager/core/navigation/ExpenseManagerScreens.kt`
- `core/settings/src/main/java/com/naveenapps/expensemanager/core/settings/data/repository/NumberFormatRepositoryImpl.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/create/TransactionCreateViewModel.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/customer/CustomerLedgerAction.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/customer/CustomerLedgerScreen.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/customer/CustomerLedgerState.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/customer/CustomerLedgerViewModel.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/di/TransactionViewModelModule.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/ledger/LedgerComponents.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/supplier/SupplierLedgerAction.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/supplier/SupplierLedgerScreen.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/supplier/SupplierLedgerState.kt`
- `feature/transaction/src/main/kotlin/com/naveenapps/expensemanager/feature/transaction/supplier/SupplierLedgerViewModel.kt`
- `feature/transaction/src/main/res/values-ar/strings.xml`
- `feature/transaction/src/main/res/values/strings.xml`
- `feature/transaction/src/test/kotlin/com/naveenapps/expensemanager/feature/transaction/customer/CustomerLedgerViewModelTest.kt`
- `feature/transaction/src/test/kotlin/com/naveenapps/expensemanager/feature/transaction/supplier/SupplierLedgerViewModelTest.kt`
- `docs/PHASE1_REPORT_AR.md`
