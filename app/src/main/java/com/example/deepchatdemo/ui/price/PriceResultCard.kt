package com.example.deepchatdemo.ui.price

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.example.deepchatdemo.price.PriceLookupResult

@Composable
internal fun PriceResultCard(
    item: PriceLookupResult,
    modifier: Modifier = Modifier
) {
    PriceGlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        alpha = 0.58f,
        elevation = 14.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                ProductImage(
                    imageUrl = item.imageUrl,
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
    }
}

@Composable
private fun ProductImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.56f))
            .border(1.dp, Color.White.copy(alpha = 0.80f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl.isBlank()) {
            ProductImagePlaceholder()
        } else {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            ) {
                if (painter.state is AsyncImagePainter.State.Success) {
                    SubcomposeAsyncImageContent()
                } else {
                    ProductImagePlaceholder()
                }
            }
        }
    }
}

@Composable
private fun ProductImagePlaceholder() {
    Icon(
        imageVector = Icons.Rounded.Image,
        contentDescription = null,
        tint = PriceLookupColors.Muted.copy(alpha = 0.72f),
        modifier = Modifier.size(32.dp)
    )
}
