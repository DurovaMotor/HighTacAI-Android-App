package com.example.deepchatdemo.ui.price

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.platform.network.PlatformImageUrlResolver
import com.example.deepchatdemo.price.PriceLookupResult
import kotlinx.coroutines.CancellationException

@Composable
internal fun PriceResultCard(
    item: PriceLookupResult,
    imageUrlResolver: PlatformImageUrlResolver,
    imageLoader: ImageLoader,
    resolveImageUrl: suspend (PriceLookupResult, Boolean) -> String,
    modifier: Modifier = Modifier,
    lightBinding: LightBinding? = null,
    onClick: () -> Unit = {},
    onBindLight: () -> Unit = {},
    onTurnOnLight: () -> Unit = {},
    onTurnOffLight: () -> Unit = {}
) {
    PriceGlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        alpha = 0.58f,
        elevation = 14.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onClick)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    ProductImage(
                        item = item,
                        imageUrlResolver = imageUrlResolver,
                        imageLoader = imageLoader,
                        resolveImageUrl = resolveImageUrl,
                        contentDescription = item.nameCn.ifBlank { "产品图片" },
                        modifier = Modifier.size(84.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(84.dp)
                    ) {
                        Text(
                            text = item.nameCn.ifBlank { item.nameEn }.ifBlank { "暂无记录" },
                            color = PriceLookupColors.Ink,
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "标准价：${priceOrNoRecord(item.standardPrice)}",
                            color = PriceLookupColors.Muted,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "最新价：${priceOrNoRecord(item.latestPrice)}",
                            color = PriceLookupColors.Purple,
                            fontSize = 15.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.54f))
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "产品编码：${displayOrNoRecord(item.code)} | 品牌：${displayOrNoRecord(item.brand)} | 适用车型：${displayOrNoRecord(item.models)}",
                    color = PriceLookupColors.Ink,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(10.dp))
            if (lightBinding == null) {
                PriceGradientButton(
                    text = "扫码绑定灯条",
                    active = item.code.isNotBlank(),
                    enabled = item.code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBindLight
                )
            } else {
                Text(
                    text = "已绑定：${lightBinding.tagId}",
                    color = PriceLookupColors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PriceGradientButton(
                        text = "亮灯",
                        modifier = Modifier.weight(1f),
                        onClick = onTurnOnLight
                    )
                    PriceGradientButton(
                        text = "灭灯",
                        active = false,
                        modifier = Modifier.weight(1f),
                        onClick = onTurnOffLight
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProductImage(
    item: PriceLookupResult,
    imageUrlResolver: PlatformImageUrlResolver,
    imageLoader: ImageLoader,
    resolveImageUrl: suspend (PriceLookupResult, Boolean) -> String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    var resolvedImageUrl by remember(item.id) { mutableStateOf<String?>(null) }
    var requestGeneration by remember(item.id) { mutableStateOf(0) }
    var forceRefreshAttempted by rememberSaveable(item.id) { mutableStateOf(false) }

    suspend fun resolveAndApply(forceRefresh: Boolean) {
        val resolvedUrl = try {
            resolveImageUrl(item, forceRefresh)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                PRICE_IMAGE_LOG_TAG,
                "Price image resolution failed: forceRefresh=$forceRefresh, " +
                    "type=${error.javaClass.simpleName}"
            )
            ""
        }
        val safeUrl = imageUrlResolver.sanitize(resolvedUrl)
        if (resolvedUrl.isNotBlank() && safeUrl.isBlank()) {
            Log.w(
                PRICE_IMAGE_LOG_TAG,
                "Price image resolution was rejected by the platform URL policy: " +
                    "forceRefresh=$forceRefresh"
            )
        }
        resolvedImageUrl = safeUrl
        requestGeneration += 1
    }

    LaunchedEffect(item.id) {
        resolveAndApply(forceRefresh = false)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.56f))
            .border(1.dp, Color.White.copy(alpha = 0.80f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        val safeImageUrl = resolvedImageUrl
        if (safeImageUrl.isNullOrBlank()) {
            ProductImagePlaceholder()
        } else {
            key(requestGeneration) {
                SubcomposeAsyncImage(
                    model = safeImageUrl,
                    contentDescription = contentDescription,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (val state = painter.state) {
                        is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                        is AsyncImagePainter.State.Error -> {
                            ProductImagePlaceholder()
                            LaunchedEffect(state) {
                                val willForceRefresh = !forceRefreshAttempted
                                Log.w(
                                    PRICE_IMAGE_LOG_TAG,
                                    "Price image load failed: " +
                                        "type=${state.result.throwable.javaClass.simpleName}, " +
                                        "willForceRefresh=$willForceRefresh"
                                )
                                if (willForceRefresh) {
                                    forceRefreshAttempted = true
                                    resolveAndApply(forceRefresh = true)
                                }
                            }
                        }
                        else -> ProductImagePlaceholder()
                    }
                }
            }
        }
    }
}

private const val PRICE_IMAGE_LOG_TAG = "HighTacAI"

@Composable
private fun ProductImagePlaceholder() {
    Icon(
        imageVector = Icons.Rounded.Image,
        contentDescription = null,
        tint = PriceLookupColors.Muted.copy(alpha = 0.72f),
        modifier = Modifier.size(32.dp)
    )
}
