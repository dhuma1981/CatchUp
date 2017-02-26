package io.sweers.catchup.ui.controllers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import butterknife.BindView;
import butterknife.Unbinder;
import com.bluelinelabs.conductor.RouterTransaction;
import com.bluelinelabs.conductor.changehandler.VerticalChangeHandler;
import com.google.android.flexbox.FlexboxLayout;
import fisk.chipcloud.ChipCloud;
import fisk.chipcloud.ChipCloudConfig;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import io.sweers.catchup.R;
import io.sweers.catchup.data.smmry.SmmryService;
import io.sweers.catchup.data.smmry.model.SmmryRequestBuilder;
import io.sweers.catchup.data.smmry.model.SmmryResponse;
import io.sweers.catchup.injection.scopes.PerController;
import io.sweers.catchup.rx.autodispose.AutoDispose;
import io.sweers.catchup.rx.observers.adapter.SingleObserverAdapter;
import io.sweers.catchup.ui.activity.ActivityComponent;
import io.sweers.catchup.ui.activity.MainActivity;
import io.sweers.catchup.ui.base.ButterKnifeController;
import io.sweers.catchup.ui.base.ServiceController;
import javax.inject.Inject;

/**
 * Overlay controller for displaying Smmry API results.
 */
public class SmmryController extends ButterKnifeController {

  @Inject SmmryService smmryService;

  private String url;
  @ColorInt private int accentColor;
  @BindView(R.id.loading_view) View loadingView;
  @BindView(R.id.progress) ProgressBar progressBar;
  @BindView(R.id.content_container) ViewGroup content;
  @BindView(R.id.tags_container) FlexboxLayout tags;
  @BindView(R.id.title) TextView title;
  @BindView(R.id.summary) TextView summary;

  public static <T> Consumer<T> showFor(ServiceController controller, String url) {
    return t -> {
      // TODO Optimize this
      // Exclude images
      // Summarize reddit selftexts
      controller.getRouter()
          .pushController(RouterTransaction.with(new SmmryController(url,
              controller.getServiceThemeColor()))
              .pushChangeHandler(new VerticalChangeHandler())
              .popChangeHandler(new VerticalChangeHandler()));
    };
  }

  public SmmryController() {
  }

  public SmmryController(String url, @ColorInt int accentColor) {
    this.url = url;
    this.accentColor = accentColor;
  }

  @Override
  protected View inflateView(@NonNull LayoutInflater inflater, @NonNull ViewGroup container) {
    return inflater.inflate(R.layout.controller_smmry, container, false);
  }

  @Override protected void onViewBound(@NonNull View view) {
    super.onViewBound(view);
    progressBar.setIndeterminateTintList(ColorStateList.valueOf(accentColor));
    DaggerSmmryController_Component.builder()
        .activityComponent(((MainActivity) getActivity()).getComponent())
        .build()
        .inject(this);
  }

  @Override protected void onAttach(@NonNull View view) {
    super.onAttach(view);
    smmryService.summarizeUrl(SmmryRequestBuilder.forUrl(url)
        .withBreak(true)
        .keywordCount(5)
        .sentenceCount(5)
        .build())
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(AutoDispose.single(this)
            .around(new SingleObserverAdapter<SmmryResponse>() {
              @Override public void onSuccess(SmmryResponse value) {
                if (value.apiMessage() != null) {
                  Toast.makeText(getActivity(),
                      "Smmry Error: " + value.errorCode() + " - " + value.apiMessage(),
                      Toast.LENGTH_LONG)
                      .show();
                  getRouter().popController(SmmryController.this);
                } else {
                  showSummary(value);
                }
              }

              @Override public void onError(Throwable e) {
                Toast.makeText(getActivity(), "API error", Toast.LENGTH_SHORT)
                    .show();
              }
            }));
  }

  private void showSummary(SmmryResponse smmry) {
    ChipCloudConfig config = new ChipCloudConfig().selectMode(ChipCloud.SelectMode.none)
        .uncheckedChipColor(accentColor)
        .uncheckedTextColor(Color.WHITE)
        .typeface(Typeface.DEFAULT_BOLD)
        .useInsetPadding(true);

    ChipCloud chipCloud = new ChipCloud(tags.getContext(), tags, config);
    if (smmry.keywords() != null) {
      for (String s : smmry.keywords()) {
        chipCloud.addChip(s.toUpperCase());
      }
    }
    title.setText(smmry.title());
    summary.setText(smmry.content()
        .replace("[BREAK]", "\n\n"));
    loadingView.animate()
        .alpha(0f)
        .setListener(new AnimatorListenerAdapter() {
          @Override public void onAnimationEnd(Animator animation) {
            loadingView.setVisibility(View.GONE);
          }
        });
    content.setAlpha(0f);
    content.setVisibility(View.VISIBLE);
    content.animate()
        .alpha(1f);
  }

  @Override protected Unbinder bind(@NonNull View view) {
    return new SmmryController_ViewBinding(this, view);
  }

  @PerController
  @dagger.Component(dependencies = ActivityComponent.class)
  public interface Component {
    void inject(SmmryController controller);
  }
}