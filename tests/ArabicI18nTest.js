/* Arabic on old TVs without Intl, and on TVs with modern locale support. */
var assert = require('assert');
var fs = require('fs');
var vm = require('vm');
var path = require('path');
var root = path.join(__dirname, '..');
var ar = JSON.parse(fs.readFileSync(path.join(root, 'app/assets/i18n/ar.json'), 'utf8'));
var en = JSON.parse(fs.readFileSync(path.join(root, 'app/assets/i18n/en.json'), 'utf8'));
var original = JSON.parse(fs.readFileSync(path.join(root, 'ar.json'), 'utf8')).translations;
assert.deepStrictEqual(Object.keys(ar).sort(), Object.keys(en).sort());
Object.keys(original).forEach(function (key) {
  if (Object.prototype.hasOwnProperty.call(en, key)) assert.strictEqual(ar[key], original[key]);
});

function check(intl) {
  var context = {
    window: {I18N_CATALOGUES: {ar: ar}},
    navigator: {language: 'ar-EG'},
    document: {documentElement: {}},
    Intl: intl
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(root, 'web/strings.js'), 'utf8'), context);
  var data = fs.readFileSync(path.join(root, 'web/data.js'), 'utf8');
  vm.runInContext(data.substring(0, data.indexOf('var Place =')), context);
  var i18n = context.I18N;
  i18n.set('auto');
  assert.strictEqual(i18n.lang, 'ar');
  assert.strictEqual(i18n.languageName('ar'), 'العربية');
  assert.strictEqual(i18n.tr('Settings'), original.Settings);
  assert.strictEqual(i18n.trf('Moving {app}, position {n} of {total}', {app: 'YouTube', n: 2, total: 4}),
    'نقل YouTube، الموضع 2 من 4');
  var key = 'plural|one:{n} photo|other:{n} photos';
  [0, 1, 2, 3, 10, 11, 99, 100, 102, 103].forEach(function (n) {
    var category = n === 0 ? 'zero' : n === 1 ? 'one' : n === 2 ? 'two'
      : n % 100 >= 3 && n % 100 <= 10 ? 'few' : n % 100 >= 11 ? 'many' : 'other';
    var forms = ar[key].split('|');
    var form = forms.filter(function (value) { return value.indexOf(category + ':') === 0; })[0];
    assert.strictEqual(i18n.plural(key, n), form.substring(category.length + 1).replace('{n}', n));
  });
  if (!intl) {
    var morning = new Date(2026, 0, 5, 9, 7).getTime();
    var evening = new Date(2026, 0, 5, 21, 7).getTime();
    assert.strictEqual(i18n.formatDate('format|h:mm a', morning), '9:07 ص');
    assert.strictEqual(i18n.formatDate('format|h:mm a', evening), '9:07 م');
    assert.strictEqual(i18n.formatDate('format|EEEE, MMMM d', morning), 'الاثنين، 5 يناير');
  }
  i18n.set('en');
  assert.strictEqual(i18n.tr('Settings'), 'Settings');
}
check(undefined);
check(Intl);
console.log('Arabic catalogues, plural rules, dates and language switching passed.');
