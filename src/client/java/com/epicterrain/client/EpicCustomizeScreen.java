package com.epicterrain.client;

import java.util.function.IntConsumer;

import com.epicterrain.worldgen.EpicAmount;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** The "Customize" screen for the Epic Terrain world type: one slider, 1-100%. */
public final class EpicCustomizeScreen extends Screen {
	private static final int SLIDER_WIDTH = 200;

	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final Screen parent;
	private final IntConsumer onDone;
	private int percent;

	public EpicCustomizeScreen(Screen parent, int percent, IntConsumer onDone) {
		super(Component.translatable("epicterrain.customize.title"));
		this.parent = parent;
		this.percent = EpicAmount.clamp(percent);
		this.onDone = onDone;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.getTitle(), this.font);

		LinearLayout body = this.layout.addToContents(LinearLayout.vertical().spacing(8));
		body.defaultCellSetting().alignHorizontallyCenter();
		body.addChild(new PercentSlider(SLIDER_WIDTH, this.percent, value -> this.percent = value));
		body.addChild(new StringWidget(Component.translatable("epicterrain.customize.hint.scale"), this.font));
		body.addChild(new StringWidget(Component.translatable("epicterrain.customize.hint.default", EpicAmount.DEFAULT), this.font));

		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
			this.onDone.accept(this.percent);
			this.onClose();
		}).build());
		footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).build());

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	/** Whole-percent slider; the mouse snaps to integers and the arrow keys move one percent at a time. */
	private static final class PercentSlider extends AbstractSliderButton {
		private static final int STEPS = EpicAmount.MAX - EpicAmount.MIN;

		private final IntConsumer onChange;
		private int percent;

		PercentSlider(int width, int percent, IntConsumer onChange) {
			super(0, 0, width, DEFAULT_HEIGHT, label(percent), (percent - EpicAmount.MIN) / (double) STEPS);
			this.percent = percent;
			this.onChange = onChange;
		}

		private static Component label(int percent) {
			return Component.translatable("epicterrain.customize.amount", percent);
		}

		@Override
		protected void setValue(double newValue) {
			super.setValue(Math.round(Math.max(0.0, Math.min(1.0, newValue)) * STEPS) / (double) STEPS);
		}

		@Override
		protected void updateMessage() {
			this.setMessage(label(this.percent));
		}

		@Override
		protected void applyValue() {
			this.percent = EpicAmount.MIN + (int) Math.round(this.value * STEPS);
			this.onChange.accept(this.percent);
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (this.canChangeValue && (event.isLeft() || event.isRight())) {
				this.setValue(this.value + (event.isLeft() ? -1.0 : 1.0) / STEPS);
				return true;
			}
			return super.keyPressed(event);
		}
	}
}
