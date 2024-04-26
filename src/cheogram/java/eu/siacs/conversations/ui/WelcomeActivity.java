package eu.siacs.conversations.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.security.KeyChain;
import android.security.KeyChainAliasCallback;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityWelcomeBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.InstallReferrerUtils;
import eu.siacs.conversations.utils.SignupUtils;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;

import static eu.siacs.conversations.utils.PermissionUtils.allGranted;
import static eu.siacs.conversations.utils.PermissionUtils.writeGranted;

public class WelcomeActivity extends XmppActivity implements XmppConnectionService.OnAccountCreated, XmppConnectionService.OnAccountUpdate, KeyChainAliasCallback {

    private static final int REQUEST_IMPORT_BACKUP = 0x63fb;

    private XmppUri inviteUri;
    private Account onboardingAccount = null;

    public static void launch(AppCompatActivity activity) {
        Intent intent = new Intent(activity, WelcomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }

    public void onInstallReferrerDiscovered(final Uri referrer) {
        Log.d(Config.LOGTAG, "welcome activity: on install referrer discovered " + referrer);
        if ("xmpp".equalsIgnoreCase(referrer.getScheme())) {
            final XmppUri xmppUri = new XmppUri(referrer);
            runOnUiThread(() -> processXmppUri(xmppUri));
        } else {
            Log.i(Config.LOGTAG, "install referrer was not an XMPP uri");
        }
    }

    private void processXmppUri(final XmppUri xmppUri) {
        if (!xmppUri.isValidJid()) {
            return;
        }
        final String preAuth = xmppUri.getParameter(XmppUri.PARAMETER_PRE_AUTH);
        final Jid jid = xmppUri.getJid();
        final Intent intent;
        if (xmppUri.isAction(XmppUri.ACTION_REGISTER)) {
            intent = SignupUtils.getTokenRegistrationIntent(this, jid, preAuth);
        } else if (xmppUri.isAction(XmppUri.ACTION_ROSTER) && "y".equals(xmppUri.getParameter(XmppUri.PARAMETER_IBR))) {
            intent = SignupUtils.getTokenRegistrationIntent(this, jid.getDomain(), preAuth);
            intent.putExtra(StartConversationActivity.EXTRA_INVITE_URI, xmppUri.toString());
        } else {
            intent = null;
        }
        if (intent != null) {
            startActivity(intent);
            finish();
            return;
        }
        this.inviteUri = xmppUri;
    }

    @Override
    protected synchronized void refreshUiReal() {
        if (onboardingAccount == null) return;
        if (onboardingAccount.getStatus() != Account.State.ONLINE) return;

        Intent intent = new Intent(this, StartConversationActivity.class);
        intent.putExtra("init", true);
        intent.putExtra(EXTRA_ACCOUNT, onboardingAccount.getJid().asBareJid().toEscapedString());
        onboardingAccount = null;
        startActivity(intent);
        finish();
    }

    @Override
    public void onAccountUpdate() {
        refreshUi();
    }

    @Override
    protected void onBackendConnected() {

    }

    @Override
    public void onStart() {
        super.onStart();
        new InstallReferrerUtils(this);
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onNewIntent(Intent intent) {
        if (intent != null) {
            setIntent(intent);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (getResources().getBoolean(R.bool.portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        super.onCreate(savedInstanceState);
        getPreferences().edit().putStringSet("pstn_gateways", new HashSet<>()).apply();
        ActivityWelcomeBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_welcome);
        binding.slideshowPager.setAdapter(new WelcomePagerAdapter(binding.slideshowPager));
        binding.dotsIndicator.setViewPager(binding.slideshowPager);
        binding.slideshowPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            public void onPageScrollStateChanged(int state) { }
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) { }

            public void onPageSelected(int position) {
                binding.buttonNext.setVisibility(position > 1 ? View.GONE : View.VISIBLE);
                binding.buttonPrivacy.setVisibility(position < 2 ? View.GONE : View.VISIBLE);
            }
        });
        binding.buttonNext.setOnClickListener((v) ->
            binding.slideshowPager.setCurrentItem(binding.slideshowPager.getCurrentItem() + 1)
        );
        binding.buttonPrivacy.setOnClickListener((v) ->
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://cheogram.com/android-privacy.html")))
        );
        configureActionBar(getSupportActionBar(), false);
        binding.registerNewAccount.setOnClickListener(v -> {
            if (hasInviteUri()) {
                final Intent intent = new Intent(this, MagicCreateActivity.class);
                addInviteUri(intent);
                startActivity(intent);
            } else {
                binding.registerNewAccount.setText("Working...");
                binding.registerNewAccount.setEnabled(false);
                onboardingAccount = new Account(Jid.ofLocalAndDomain(UUID.randomUUID().toString(), Config.ONBOARDING_DOMAIN.toEscapedString()), CryptoHelper.createPassword(new SecureRandom()));
                onboardingAccount.setOption(Account.OPTION_REGISTER, true);
                onboardingAccount.setOption(Account.OPTION_FIXED_USERNAME, true);
                xmppConnectionService.createAccount(onboardingAccount);
            }
        });
        binding.useExisting.setOnClickListener(v -> {
            final List<Account> accounts = xmppConnectionService.getAccounts();
            Intent intent = new Intent(WelcomeActivity.this, EditAccountActivity.class);
            intent.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, false);
            if (accounts.size() == 1) {
                intent.putExtra("jid", accounts.get(0).getJid().asBareJid().toString());
                intent.putExtra("init", true);
            } else if (accounts.size() >= 1) {
                intent = new Intent(WelcomeActivity.this, ManageAccountActivity.class);
            }
            addInviteUri(intent);
            startActivity(intent);
        });
        binding.useSnikket.setOnClickListener(v -> {
            final List<Account> accounts = xmppConnectionService.getAccounts();
            Intent intent = new Intent(WelcomeActivity.this, EditAccountActivity.class);
            intent.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, false);
            intent.putExtra("snikket", true);
            if (accounts.size() == 1) {
                intent.putExtra("jid", accounts.get(0).getJid().asBareJid().toString());
                intent.putExtra("init", true);
            } else if (accounts.size() >= 1) {
                intent = new Intent(WelcomeActivity.this, ManageAccountActivity.class);
            }
            addInviteUri(intent);
            startActivity(intent);
        });

        binding.useBackup.setOnClickListener(v -> {
            if (hasStoragePermission(REQUEST_IMPORT_BACKUP)) {
                startActivity(new Intent(this, ImportBackupActivity.class));
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.welcome_menu, menu);
        final MenuItem scan = menu.findItem(R.id.action_scan_qr_code);
        scan.setVisible(Compatibility.hasFeatureCamera(this));
        return super.onCreateOptionsMenu(menu);
    }



    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_import_backup:
                if (hasStoragePermission(REQUEST_IMPORT_BACKUP)) {
                    startActivity(new Intent(this, ImportBackupActivity.class));
                }
                break;
            case R.id.action_scan_qr_code:
                UriHandlerActivity.scan(this, true);
                break;
            case R.id.action_add_account_with_cert:
                addAccountFromKey();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void addAccountFromKey() {
        try {
            KeyChain.choosePrivateKeyAlias(this, this, null, null, null, -1, null);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.device_does_not_support_certificates, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void alias(final String alias) {
        if (alias != null) {
            xmppConnectionService.createAccountFromKey(alias, this);
        }
    }

    @Override
    public void onAccountCreated(final Account account) {
        final Intent intent = new Intent(this, EditAccountActivity.class);
        intent.putExtra("jid", account.getJid().asBareJid().toEscapedString());
        intent.putExtra("init", true);
        addInviteUri(intent);
        startActivity(intent);
    }

    @Override
    public void informUser(final int r) {
        runOnUiThread(() -> Toast.makeText(this, r, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults);
        if (grantResults.length > 0) {
            if (allGranted(grantResults)) {
                switch (requestCode) {
                    case REQUEST_IMPORT_BACKUP:
                        startActivity(new Intent(this, ImportBackupActivity.class));
                        break;
                }
            } else if (Arrays.asList(permissions).contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(this, R.string.no_storage_permission, Toast.LENGTH_SHORT).show();
            }
        }
        if (writeGranted(grantResults, permissions)) {
            if (xmppConnectionService != null) {
                xmppConnectionService.restartFileObserver();
            }
        }
    }

    protected boolean hasInviteUri() {
        final Intent from = getIntent();
        if (from != null && from.hasExtra(StartConversationActivity.EXTRA_INVITE_URI)) return true;
        return this.inviteUri != null;
    }

    public void addInviteUri(Intent to) {
        final Intent from = getIntent();
        if (from != null && from.hasExtra(StartConversationActivity.EXTRA_INVITE_URI)) {
            final String invite = from.getStringExtra(StartConversationActivity.EXTRA_INVITE_URI);
            to.putExtra(StartConversationActivity.EXTRA_INVITE_URI, invite);
        } else if (this.inviteUri != null) {
            Log.d(Config.LOGTAG, "injecting referrer uri into on-boarding flow");
            to.putExtra(StartConversationActivity.EXTRA_INVITE_URI, this.inviteUri.toString());
        }
    }

    class WelcomePagerAdapter extends PagerAdapter {
        protected View[] pages;

        public WelcomePagerAdapter(ViewPager p) {
            super();
            pages = new View[]{ p.getChildAt(0), p.getChildAt(1), p.getChildAt(2) };
            for (View v : pages) {
                p.removeView(v);
            }
        }

        @Override
        public int getCount() {
            return 3;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            container.addView(pages[position]);
            return pages[position];
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object o) {
            return view == o;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, Object o) {
            container.removeView(pages[position]);
        }
    }
}
